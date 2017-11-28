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
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.thread.Scheduler;

/**
 * An Abstract implementation of a Timeout Task.
 * <p>
 * This implementation is optimised assuming that the timeout will mostly
 * be cancelled and then reused with a similar value.
 */
public abstract class NonBlockingCyclicTimeoutTask implements CyclicTimeoutTask
{
    private final static Expiry NOT_SET = new Expiry(MAX_VALUE,null);
    
    /* The underlying scheduler to use */
    private final Scheduler _scheduler;

    /* reference to the current expiry and chain of Schedules */
    private final AtomicReference<Expiry> _expiry = new AtomicReference<>(NOT_SET);
    
    /**
     * @param scheduler A scheduler used to schedule checks for the idle timeout.
     */
    public NonBlockingCyclicTimeoutTask(Scheduler scheduler)
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
        schedules.increment();
        long now = System.nanoTime();
        long expireAtNanos = now + units.toNanos(delay);
        
        Schedule new_schedule;
        while(true)
        {
            Expiry old_expiry = _expiry.get();
            new_schedule = null;

            if ( old_expiry._expireAt!=MAX_VALUE)
                throw new IllegalStateException("Timeout pending");
                
            // We need a schedule chain that starts with a scheduledAt time at or
            // before our expiry time
            Schedule scheduled = old_expiry._schedule;
            if (scheduled==null || scheduled._scheduledAt>expireAtNanos)
                scheduled = new_schedule = new Schedule(now,expireAtNanos,scheduled);
            
            // Create the new expiry state
            Expiry new_expiry = new Expiry(expireAtNanos,scheduled);
            
            if (_expiry.compareAndSet(old_expiry,new_expiry))
                break;
            casloops.increment();
        }

        // If we created a new head of the schedule chain, we need to actually schedule it
        if (new_schedule!=null)
            new_schedule.schedule(now);
    }
    
    /** 
     * Reschedule a timer, even if already set, cancelled or expired
     * @param delay The period to delay before the timer expires.
     * @param units The units of the delay period.
     * @return True if the timer was already set.
     */
    public boolean reschedule(long delay, TimeUnit units)
    {   
        reschedules.increment();
        long now = System.nanoTime();
        long expireAtNanos = now + units.toNanos(delay);
        
        boolean was_scheduled;
        Schedule new_schedule;
        while(true)
        {
            Expiry old_expiry = _expiry.get();
            
            new_schedule = null;
            was_scheduled = old_expiry._expireAt!=MAX_VALUE;

            // We need a schedule chain that starts with a scheduledAt time at or
            // before our expiry time
            Schedule scheduled = old_expiry._schedule;
            if (scheduled==null || scheduled._scheduledAt>expireAtNanos)
                scheduled = new_schedule = new Schedule(now,expireAtNanos,scheduled);

            // Create the new expiry state
            Expiry new_expiry = new Expiry(expireAtNanos,scheduled);
            
            if (_expiry.compareAndSet(old_expiry,new_expiry))
                break;
            casloops.increment();
        }

        // If we created a new head of the schedule chain, we need to actually schedule it
        if (new_schedule!=null)
            new_schedule.schedule(now);
        
        return was_scheduled;
    }
    
    public boolean cancel()
    {
        boolean was_scheduled;
        Expiry old_expiry;
        Expiry new_expiry;
        do
        {
            old_expiry = _expiry.get();
            was_scheduled = old_expiry._expireAt!=MAX_VALUE;
            Schedule scheduled = old_expiry._schedule;
            new_expiry = scheduled==null?NOT_SET:new Expiry(MAX_VALUE,scheduled);
        }
        while(!_expiry.compareAndSet(old_expiry,new_expiry));

        return was_scheduled;
    }
    
    public abstract void onTimeoutExpired();

    public void destroy()
    {
        Expiry expiry = _expiry.getAndSet(null);
        Schedule schedule = expiry==null?null:expiry._schedule;
        while (schedule!=null)
        {
            schedule.destroy();
            schedule = schedule._chain;
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
     * A Scheduled expiry of a real timer.
     */
    private class Schedule implements Runnable
    {
        final long _scheduledAt;
        final Schedule _chain;
        volatile Scheduler.Task _task;
        
        Schedule(long now, long scheduledAt, Schedule chain)
        {
            _scheduledAt = scheduledAt;
            _chain = chain;
        }
        
        void schedule(long now)
        {
            realschedules.increment();
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
            boolean expired; 
            
            long now;
            Schedule new_schedule;
            while (true)
            {
                now = System.nanoTime();
                new_schedule = null;
                expired = false;
                Expiry old_expiry = _expiry.get();
                Expiry new_expiry = old_expiry;

                // look for ourselves at the current Expiry and the Schedule chain.
                // We SHOULD be the head of the chain, but if there are strange dispatch
                // delays from a Scheduler, then it is conceivable that Schedules will be
                // expired out of order.   We handle this by later Schedules skipping any
                // prior Schedules in the chain and Schedules become noops if they are not 
                // in the chain.
                
                Schedule schedule = old_expiry._schedule;
                while (schedule!=null)
                {
                    Schedule last = schedule;
                    schedule = schedule._chain;
                    if (last==this)
                    {
                        // This scheduled is still in the chain, so we must act
                        if (old_expiry._expireAt <= now)
                        {
                            // Expired
                            expired = true;
                            new_expiry = new Expiry(MAX_VALUE,schedule);
                        }
                        else if (old_expiry._expireAt!=MAX_VALUE)
                        {
                            // Not expired yet, need to update Schedule
                            if (schedule==null || schedule._scheduledAt>old_expiry._expireAt)
                                schedule = new_schedule = new Schedule(now,old_expiry._expireAt,schedule);
                            new_expiry = new Expiry(old_expiry._expireAt,schedule);
                        }
                        else
                        {
                            // Not scheduled, but preserve scheduled chain.
                            new_expiry = schedule==null?NOT_SET:new Expiry(MAX_VALUE,schedule);
                        }
                        break;
                    }
                }

                // Loop until we succeed in changing state or we are a noop!
                if (new_expiry==old_expiry || _expiry.compareAndSet(old_expiry,new_expiry))
                    break;
                casloops.increment();
            }

            // If we created a new head of the schedule chain, we need to actually schedule it
            if (new_schedule!=null)
                new_schedule.schedule(now);
            
            // If we expired, then do the callback
            if (expired)
                onTimeoutExpired();
        }
        
        @Override
        public String toString()
        {
            return String.format("Scheduled@%x:%d->%s",hashCode(),_scheduledAt,_chain);
        }
    }
    
    final static LongAdder schedules = new LongAdder();
    final static LongAdder reschedules = new LongAdder();
    final static LongAdder casloops = new LongAdder();
    final static LongAdder realschedules = new LongAdder();
    public static void dump()
    {
        System.err.printf("%n=============================%n");
        System.err.printf("schedules  =%,15d%n",schedules.sumThenReset());
        System.err.printf("reschedules=%,15d%n",reschedules.sumThenReset());
        System.err.printf("casloops   =%,15d%n",casloops.sumThenReset());
        System.err.printf("realscheds =%,15d%n",realschedules.sumThenReset());
        System.err.printf("-----------------------------%n");
    }
}
