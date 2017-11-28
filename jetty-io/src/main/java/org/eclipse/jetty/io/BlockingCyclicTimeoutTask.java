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

import org.eclipse.jetty.util.thread.Scheduler;

/**
 * An Abstract implementation of a Timeout Task.
 * <p>
 * This implementation is optimised assuming that the timeout will mostly
 * be cancelled and then reused with a similar value.
 */
public abstract class BlockingCyclicTimeoutTask implements CyclicTimeoutTask
{
    /* The underlying scheduler to use */
    private final Scheduler _scheduler;

    /* 
     * The time at which this task will expire (or MAX_VALUE if it will never expire).
     */
    private long _expireAtNanos = MAX_VALUE;
    
    /*
     * A reference to a scheduled expiry of the underlying scheduler chain. 
     */
    private Scheduled _scheduled ;
    

    /**
     * @param scheduler A scheduler used to schedule checks for the idle timeout.
     */
    public BlockingCyclicTimeoutTask(Scheduler scheduler)
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
        synchronized(this)
        {
            if (_expireAtNanos!=MAX_VALUE)
                throw new IllegalStateException("Timeout pending");
            
            // Calculate the nanotime at which we will expire
            long now = System.nanoTime();
            _expireAtNanos = now + units.toNanos(delay);

            schedule(now);
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
        boolean was_scheduled;
        synchronized(this)
        {
            was_scheduled = _expireAtNanos!=MAX_VALUE;

            // Calculate the nanotime at which we will expire
            long now = System.nanoTime();
            _expireAtNanos = now + units.toNanos(delay);

            schedule(now);
        }
        return was_scheduled;
    }
    
    private void schedule(long now)
    {
        if (_scheduled==null || _scheduled._scheduledAt>_expireAtNanos)
            _scheduled = new Scheduled(now,_expireAtNanos,_scheduled);
    }
   
    public boolean cancel()
    {
        boolean was_scheduled;
        synchronized(this)
        {
            was_scheduled = _expireAtNanos!=MAX_VALUE;
            _expireAtNanos = MAX_VALUE;
        }
        return was_scheduled;
    }
    
    public abstract void onTimeoutExpired();

    public void destroy()
    {
        synchronized(this)
        {
            _expireAtNanos = MAX_VALUE;

            Scheduled scheduled = _scheduled;
            while (scheduled!=null)
            {
                scheduled._task.cancel();
                scheduled = scheduled._chain;
            }
        }
    }
    
    /**
     * A Scheduled expiry of a real timer.
     */
    private class Scheduled implements Runnable
    {
        final long _scheduledAt;
        final Scheduler.Task _task;
        final Scheduled _chain;
        
        Scheduled(long now, long scheduledAt, Scheduled chain)
        {
            _scheduledAt = scheduledAt;
            _task = _scheduler.schedule(this,scheduledAt-now,TimeUnit.NANOSECONDS);
            _chain = chain;
        }
        
        @Override
        public void run()
        {
            boolean expired = false;
            synchronized (BlockingCyclicTimeoutTask.this)
            {
                long now = System.nanoTime();
             
                // Remove ourselves from the chain (and any strangely unexpired prior Scheduled)
                Scheduled scheduled = _scheduled;
                while (scheduled!=null)
                {
                    Scheduled last = scheduled;
                    scheduled = scheduled._chain;
                    if (last==this)
                        break;
                }
                _scheduled = scheduled;
                
                
                if (now>=_expireAtNanos)
                {
                    expired = true;
                    _expireAtNanos = MAX_VALUE;
                }
                else if (now!=MAX_VALUE)
                {
                    schedule(now);
                }                    
            }
            
            if (expired)
                onTimeoutExpired();
            
        }
        
        @Override
        public String toString()
        {
            return String.format("Scheduled@%x:%d",hashCode(),_expireAtNanos);
        }
        
    }
    
}
