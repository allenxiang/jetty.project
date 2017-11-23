//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import static java.lang.Long.MAX_VALUE;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.thread.Scheduler;

/**
 * An Abstract implementation of a Timeout Task.
 * <p>
 * This implementation is optimised assuming that the timeout will mostly
 * be cancelled and then reused with a similar value.
 */
public abstract class CyclicTimeoutTask
{
    /* The underlying scheduler to use */
    private final Scheduler _scheduler;

    /* 
     * The time at which this task will expire (or MAX_VALUE if it will never expire.
     * This is the primary concurrent mechanism in this class and must be updated
     * correctly with looping compare and sets.
     */
    private final AtomicLong _expireAtNanos = new AtomicLong(Long.MAX_VALUE);
    
    /*
     * A reference to a scheduled expiry of the underlying scheduler.  This is kept
     * as an atomic reference, but it is only held as an optimization, so it is not
     * important if updates to _scheduled are lost. 
     */
    private final AtomicReference<Scheduled> _scheduled = new AtomicReference<>();
    

    /**
     * @param scheduler A scheduler used to schedule checks for the idle timeout.
     */
    public CyclicTimeoutTask(Scheduler scheduler)
    {
        _scheduler = scheduler;
    }

    public Scheduler getScheduler()
    {
        return _scheduler;
    }
    
    /** 
     * Schedule a timer.
     * @param delay The period to delay before the timer expires.
     * @param units The units of the delay period.
     * @throws IllegalStateException Thrown if the timer is already set.
     */
    public void schedule(long delay, TimeUnit units) throws IllegalStateException
    {
        // Calculate the nanotime at which we will expire
        long now = System.nanoTime();
        long expireAtNanos = now + units.toNanos(delay);
    
        // Update the atomic. If we fail, then timer was already set.
        if (!_expireAtNanos.compareAndSet(MAX_VALUE,expireAtNanos))
            throw new IllegalStateException("Timeout pending");
    
        // We've updated the expiry time, so we need a schedule to service that time.
        // Let's look if there is one already set that is good enough?
        Scheduled scheduled = _scheduled.get();
        if (scheduled==null || scheduled._scheduledAt>expireAtNanos)
        {
            // Either there is no schedule expiry, or we can see one that expires after we
            // need it. So we need our own scheduled expiry. 
            Scheduled rescheduled = new Scheduled(now,expireAtNanos);
            
            // Now that we have our own schedule, let's try to set it so it can be reused.
            // If we fail to set it, that's no problem as we are still scheduled. The only
            // down side is that subsequent calls to schedule or reschedule may create an
            // extra unnecessary schedule.
            _scheduled.compareAndSet(scheduled,rescheduled);
        }
    }
    
    /** 
     * Reschedule a timer, even if already set, cancelled or expired
     * @param delay The period to delay before the timer expires.
     * @param units The units of the delay period.
     * @return True if the timer was already set.
     */
    public boolean reschedule(long delay, TimeUnit units)
    {
        // Calculate the nanotime at which we will expire
        long now = System.nanoTime();
        long expireAtNanos = now + units.toNanos(delay);
    
        // loop until we manage to update the expiry time
        while(true)
        {
            long expireAt = _expireAtNanos.get();
            if (_expireAtNanos.compareAndSet(expireAt,expireAtNanos))
            {        
                // We've updated the expiry time, so we need a schedule to service that time.
                // Let's look if there is one already set that is good enough?
                Scheduled scheduled = _scheduled.get();
                if (scheduled==null || scheduled._scheduledAt>expireAtNanos)
                {
                    // The schedule we can see is not good enough (either null or 
                    // expires too late), so make our own. 
                    Scheduled rescheduled = new Scheduled(now,expireAtNanos);
                    
                    // We have scheduled our own timeout, so it would be good to make it 
                    // visible to other threads that may call reschedule.  But if this fails 
                    // it is not a problem as they will create their own Schedule
                    _scheduled.compareAndSet(scheduled,rescheduled);
                }
                return expireAt!=MAX_VALUE;
            }
        }
    }
   
    public boolean cancel()
    {
        return _expireAtNanos.getAndSet(MAX_VALUE)!=MAX_VALUE;
    }
    
    public abstract void onTimeoutExpired();

    public void destroy()
    {
        cancel();
        Scheduled scheduled = _scheduled.getAndSet(null);
        if (scheduled!=null)
            scheduled._task.cancel();
    }
    
    /**
     * A Scheduled expiry of a real timer.
     */
    private class Scheduled implements Runnable
    {
        final long _scheduledAt;
        final Scheduler.Task _task;
        
        Scheduled(long now, long scheduledAt)
        {
            _scheduledAt = scheduledAt;
            _task = _scheduler.schedule(this,scheduledAt-now,TimeUnit.NANOSECONDS);
        }
        
        @Override
        public void run()
        {
            // Our scheduled real timer has expired, so we must check if our real
            // expiry has passed.  We do this in a loop as we must atomically compare
            // and set to indicate the timer has expired.
            while(true)
            {
                long now = System.nanoTime();
                long expireAt = _expireAtNanos.get();
                
                // If we expireAt MAX_VALUE, we are cancelled, so return without making a new Scheduled
                if (expireAt==MAX_VALUE)
                {   
                    // We've expired, so matter what we will not be expiring again.
                    // So remove ourselves form the scheduled reference.  Doesn't matter
                    // if we fail as it indicates we've already been replaced.
                    _scheduled.compareAndSet(this,null);
                    
                    return;
                }

                // Are we actually expired?
                if (expireAt<now)
                {
                    // Update with CAS so that only one Scheduled will actually expire the
                    // timeout (as there may be multiple Scheduled instances for similar times).
                    if (!_expireAtNanos.compareAndSet(expireAt,MAX_VALUE))
                        continue; // Somebody beat us to it, so retry
                    
                    // We've expired, so matter what we will not be expiring again.
                    // So remove ourselves form the scheduled reference.  Doesn't matter
                    // if we fail as it indicates we've already been replaced.
                    _scheduled.compareAndSet(this,null);
                    
                    // Let's tell our derived selfs that we have timed out!
                    onTimeoutExpired();
                    return;
                }
                
                // Our scheduled expiry was too early for the current expiry, so
                // we need to create a new Scheduled for our actual expiry
                Scheduled scheduled = new Scheduled(now,expireAt);
                
                // We have created a new Scheduled, so our current expiry is covered.
                // As an optimisation, we set this on the atomic so it can be seen and
                // used by other calls to schedule.  If we fail, that is not a problem 
                // as it just means a redundant Scheduled will be created.
                _scheduled.compareAndSet(this,scheduled);
            }
        }
    }
    
}
