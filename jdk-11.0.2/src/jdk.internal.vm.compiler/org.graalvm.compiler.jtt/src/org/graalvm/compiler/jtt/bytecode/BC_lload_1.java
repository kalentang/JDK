/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */
/*
 * Copyright (c) 2007, 2012, Oracle and/or its affiliates. All rights reserved.
 */


package org.graalvm.compiler.jtt.bytecode;

import org.junit.Test;

import org.graalvm.compiler.jtt.JTTTest;

/*
 */
public class BC_lload_1 extends JTTTest {

    @SuppressWarnings("unused")
    public static long test(int i, long arg) {
        return arg;
    }

    @Test
    public void run0() throws Throwable {
        runTest("test", 1, 1L);
    }

    @Test
    public void run1() throws Throwable {
        runTest("test", 1, -3L);
    }

    @Test
    public void run2() throws Throwable {
        runTest("test", 1, 10000L);
    }

}
