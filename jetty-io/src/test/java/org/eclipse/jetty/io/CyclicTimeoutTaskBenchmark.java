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


import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.profile.LinuxPerfAsmProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

public class CyclicTimeoutTaskBenchmark
{
    final static int INSTANCES=10000;
            
    static Runnable NEVER = new Runnable()
    {
        @Override
        public void run()
        {    
            throw new IllegalStateException("Should never expire!");        
        }
    };

    @State(Scope.Benchmark)
    public static class SchedulerState
    {
        final ScheduledExecutorScheduler _timer = new ScheduledExecutorScheduler();
        volatile Scheduler.Task[] _task;
        LongAdder schedules = new LongAdder();

        @Setup
        public void setup()
        {
            _task = new Scheduler.Task[INSTANCES];
            for (int i=_task.length; i-->0;)
            {
                _task[i] = _timer.schedule(NEVER,10,TimeUnit.SECONDS); 
                schedules.increment();
            }
        }
        
        @TearDown
        public void tearDown()
        {
            for (int i=_task.length; i-->0;)
                _task[i].cancel();

            System.err.printf("%n=============================%n");
            System.err.printf("schedules  =%,15d%n",schedules.sumThenReset());
            System.err.printf("-----------------------------%n");
        }
    }
    
    @Benchmark
    public void benchmarkScheduler(SchedulerState state)
    {
        int instance = ThreadLocalRandom.current().nextInt(state._task.length);
        state._task[instance].cancel();
        state._task[instance] = state._timer.schedule(NEVER,10,TimeUnit.SECONDS);
        state.schedules.increment();
    }
        
    
    @State(Scope.Benchmark)
    public static class NonBlockingState
    {
        final ScheduledExecutorScheduler _timer = new ScheduledExecutorScheduler();
        NonBlockingCyclicTimeoutTask[] _task;
        
        @Setup
        public void setup()
        {
            _task = new NonBlockingCyclicTimeoutTask[INSTANCES];
            for (int i=_task.length;i-->0;)
            {
                _task[i] = new NonBlockingCyclicTimeoutTask(_timer)
                {
                    @Override
                    public void onTimeoutExpired()
                    {
                        throw new IllegalStateException("Should never expire!");
                    }
                };
                _task[i].schedule(10,TimeUnit.SECONDS);
            }
        }
        
        @TearDown
        public void tearDown()
        {
            for (int i=_task.length;i-->0;)
                _task[i].cancel();
            NonBlockingCyclicTimeoutTask.dump();
        }
    }
    

    @Benchmark
    public void benchmarkNonBlocking(NonBlockingState state)
    {
        int instance = ThreadLocalRandom.current().nextInt(state._task.length);
        state._task[instance].reschedule(10,TimeUnit.SECONDS);
    }
    
    
    
    public static void main(String[] args) throws RunnerException 
    {
        Options opt = new OptionsBuilder()
                .include(CyclicTimeoutTaskBenchmark.class.getSimpleName())
                .warmupIterations(2)
                .measurementIterations(4)
                .threads(8)
                .forks(1)
                .addProfiler(LinuxPerfAsmProfiler.class)
                .build();

        new Runner(opt).run();
    }

    
    
}
