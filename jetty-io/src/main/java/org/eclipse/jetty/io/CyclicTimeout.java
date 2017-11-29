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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.thread.Scheduler;

/**
 * An Abstract implementation of a Timeout Task.
 * <p>
 * This implementation is optimised assuming that the timeout will mostly
 * be cancelled and then reused with a similar value.
 */
public abstract class CyclicTimeout
{
    private final static Expiry NOT_SET = new Expiry(MAX_VALUE,null);
    
    /* The underlying scheduler to use */
    private final Scheduler _scheduler;

    /* reference to the current expiry and chain of Schedules */
    private final AtomicReference<Expiry> _expiry = new AtomicReference<>(NOT_SET);
    
    /**
     * @param scheduler A scheduler used to schedule checks for the idle timeout.
     */
    public CyclicTimeout(Scheduler scheduler)
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
        long now = System.nanoTime();
        long expireAtNanos = now + units.toNanos(delay);
        
        Schedule new_schedule;
        while(true)
        {
            Expiry expiry = _expiry.get();
            new_schedule = null;

            if ( expiry._expireAt!=MAX_VALUE)
                throw new IllegalStateException("Timeout pending");
                
            // is the current schedule good to use? ie before our expiry time?
            Schedule schedule = expiry._schedule;
            if (schedule==null || schedule._scheduledAt>expireAtNanos)
                // no, we neeed a new head of the schedule list
                schedule = new_schedule = new Schedule(now,expireAtNanos,schedule); 
            
            // Try to set a new Expiry. If we succeed, then we are good
            if (_expiry.compareAndSet(expiry,new Expiry(expireAtNanos, schedule)))
                break;
            
            // No! something must have changed, lets try again!
        }

        // If we created a new head of the schedule chain, we need to actually schedule it
        if (new_schedule!=null)
            new_schedule.setTheTimer(now);
    }
    
    /** 
     * Reschedule a timer, even if already set, cancelled or expired
     * @param delay The period to delay before the timer expires.
     * @param units The units of the delay period.
     * @return True if the timer was already set.
     */
    public boolean reschedule(long delay, TimeUnit units)
    {   
        long now = System.nanoTime();
        long expireAtNanos = now + units.toNanos(delay);
        
        boolean was_set_to_expire;
        Schedule new_schedule;
        while(true)
        {
            Expiry expiry = _expiry.get();
            
            new_schedule = null;
            was_set_to_expire = expiry._expireAt!=MAX_VALUE;

            // is the current schedule good to use? ie before our expiry time?
            Schedule schedule = expiry._schedule;
            if (schedule==null || schedule._scheduledAt>expireAtNanos)
                // no, we neeed a new head of the schedule list
                schedule = new_schedule = new Schedule(now,expireAtNanos,schedule); 
            
            // Try to set a new Expiry. If we succeed, then we are good
            if (_expiry.compareAndSet(expiry,new Expiry(expireAtNanos, schedule)))
                break;
            
            // No! something must have changed, lets try again!
        }

        // If we created a new head of the schedule chain, we need to actually schedule it
        // Any Schedules that were created and discarded by failed CAS, will not be in the
        // schedule chain, will not have a timer set and will be GC'd
        if (new_schedule!=null)
            new_schedule.setTheTimer(now);
        
        return was_set_to_expire;
    }
    
    public boolean cancel()
    {
        boolean was_set_to_expire;
        Expiry expiry;
        Expiry new_expiry;
        while(true)
        {
            expiry = _expiry.get();
            was_set_to_expire = expiry._expireAt!=MAX_VALUE;
            Schedule schedule = expiry._schedule;
            new_expiry = schedule==null?NOT_SET:new Expiry(MAX_VALUE,schedule);
            if (_expiry.compareAndSet(expiry,new_expiry))
                break;
        }

        return was_set_to_expire;
    }
    
    public abstract void onTimeoutExpired();

    public void destroy()
    {
        Expiry expiry = _expiry.getAndSet(null);
        Schedule schedule = expiry==null?null:expiry._schedule;
        while (schedule!=null)
        {
            schedule.destroy();
            schedule = schedule._next;
        }
    }

    /**
     * An expiry time with a link to a Schedule chain.
     */
    private static class Expiry
    {
        final long _expireAt;
        final Schedule _schedule;
        
        Expiry(long expireAt, Schedule schedule)
        {
            _expireAt = expireAt;
            _schedule = schedule;
        }
        
        @Override
        public String toString()
        {
            return String.format("Expiry@%x:%d,%s",hashCode(),_expireAt,_schedule);
        }
    }
    
    /**
     * A Schedule chain of real timer tasks.
     */
    private class Schedule implements Runnable
    {
        final long _scheduledAt;
        final Schedule _next;
        
        /* this is only used for destroy */
        volatile Scheduler.Task _task;
        
        Schedule(long now, long scheduledAt, Schedule chain)
        {
            _scheduledAt = scheduledAt;
            _next = chain;
        }
        
        void setTheTimer(long now)
        {
            _task = _scheduler.schedule(this,_scheduledAt-now,TimeUnit.NANOSECONDS);
        }
        
        void destroy()
        {
            Scheduler.Task task = _task;
            _task = null;
            if (task!=null)
                task.cancel();
        }

        @Override
        public void run()
        {
            long now;
            boolean has_expired;
            Schedule new_schedule;
            
            while (true)
            {
                // reset every iteration
                now = System.nanoTime();
                new_schedule = null;
                has_expired = false;
                Expiry expiry = _expiry.get();
                Expiry new_expiry = expiry;

                // We must look for ourselves in the current schedule list.
                // If we find ourselves, then we act and we use our tail for any new
                // schedule list, effectively removing any schedules before us in the list (and making them no-ops).
                // If we don't find ourselves, then a schedule that should have expired after us has already run
                // and removed us from the list, so we become a noop.
                
                // Walk the schedule chain looking for ourselves
                Schedule schedule = expiry._schedule;
                while (schedule!=null)
                {
                    if (schedule!=this)
                    {
                        // Not us, so look at next schedule in the list
                        schedule = schedule._next;
                        continue;
                    }

                    // We are in the schedule list! So we have to act and we know our
                    // tail has not expired (else it would have removed us from the list).
                    // Remove ourselves (any any prior Schedules) from the schedule
                    schedule = schedule._next;

                    // Have we expired?
                    if (expiry._expireAt <= now)
                    {
                        // Yes, we are Expired!
                        has_expired = true;
                        new_expiry = schedule==null?NOT_SET:new Expiry(MAX_VALUE,schedule);
                    }
                    else if (expiry._expireAt!=MAX_VALUE)
                    {
                        // We are not expired, but we are set to expire!                           
                        // Is the current schedule good to use? ie before our expiry time?
                        if (schedule==null || schedule._scheduledAt>expiry._expireAt)
                            // no, we neeed a new head of the schedule list
                            schedule = new_schedule = new Schedule(now,expiry._expireAt,schedule); 

                        new_expiry = new Expiry(expiry._expireAt,schedule);
                    }
                    else
                    {
                        // Not scheduled, but preserve scheduled chain.
                        new_expiry = schedule==null?NOT_SET:new Expiry(MAX_VALUE,schedule);
                    }
                    
                    break;
                }

                // Loop until we succeed in changing state or we are a noop!
                if (new_expiry==expiry || _expiry.compareAndSet(expiry,new_expiry))
                    break;
            }

            // If we created a new head of the schedule chain, we need to actually schedule it
            if (new_schedule!=null)
                new_schedule.setTheTimer(now);
            
            // If we expired, then do the callback
            if (has_expired)
                onTimeoutExpired();
        }
        
        @Override
        public String toString()
        {
            return String.format("Scheduled@%x:%d->%s",hashCode(),_scheduledAt,_next);
        }
    }    
}
