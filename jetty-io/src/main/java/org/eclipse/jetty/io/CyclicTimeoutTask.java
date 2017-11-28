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

import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.thread.Scheduler;

/**
 * An Abstract implementation of a Timeout Task.
 * <p>
 * This implementation is optimised assuming that the timeout will mostly
 * be cancelled and then reused with a similar value.
 */
public interface CyclicTimeoutTask
{
    Scheduler getScheduler();
    
    /** 
     * Schedule a timer.
     * @param delay The period to delay before the timer expires.
     * @param units The units of the delay period.
     * @throws IllegalStateException Thrown if the timer is already set.
     */
    void schedule(long delay, TimeUnit units) throws IllegalStateException;
    
    /** 
     * Reschedule a timer, even if already set, cancelled or expired
     * @param delay The period to delay before the timer expires.
     * @param units The units of the delay period.
     * @return True if the timer was already set.
     */
    boolean reschedule(long delay, TimeUnit units);
    
    boolean cancel();
    
    void onTimeoutExpired();

    void destroy();
}
