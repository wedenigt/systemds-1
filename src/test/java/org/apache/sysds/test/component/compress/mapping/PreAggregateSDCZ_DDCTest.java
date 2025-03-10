/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.sysds.test.component.compress.mapping;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.runtime.compress.colgroup.dictionary.ADictionary;
import org.apache.sysds.runtime.compress.colgroup.dictionary.Dictionary;
import org.apache.sysds.runtime.compress.colgroup.mapping.AMapToData;
import org.apache.sysds.runtime.compress.colgroup.offset.AOffset;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(value = Parameterized.class)
public class PreAggregateSDCZ_DDCTest {

	protected static final Log LOG = LogFactory.getLog(PreAggregateDDC_DDCTest.class.getName());

	private final AMapToData m;
	private final AMapToData tm;
	private final ADictionary td;
	private final AOffset of;
	private final int nCol;
	private final double[] expected;

	@Parameters
	public static Collection<Object[]> data() {
		ArrayList<Object[]> tests = new ArrayList<>();

		final Random r = new Random(2321522);
		final int sm = Integer.MAX_VALUE;

		create(tests, 10, 10, 5, 1, 4, r.nextInt(sm));
		create(tests, 10, 10, 5, 1, 4, r.nextInt(sm));

		create(tests, 10, 10, 5, 3, 4, r.nextInt(sm));
		create(tests, 10, 10, 5, 4, 4, r.nextInt(sm));

		create(tests, 300, 1425, 2, 2, 4, r.nextInt(sm));
		create(tests, 42, 232, 2, 2, 4, r.nextInt(sm));

		create(tests, 10000, 130, 23, 1, 50, r.nextInt(sm));
		create(tests, 10000, 23, 180, 1, 50, r.nextInt(sm));
		create(tests, 10000, 23, 180, 4, 50, r.nextInt(sm));
		create(tests, 10000, 190, 23, 3, 100, r.nextInt(sm));
		create(tests, 10000, 201, 23, 30, 1000, r.nextInt(sm));

		create(tests, 1000000, 1000, 1000, 30, 1000, r.nextInt(sm));
		create(tests, 1000000, 1000, 1000, 30, 3, r.nextInt(sm));
		create(tests, 1000000, 2, 2, 30, 3, r.nextInt(sm));

		create(tests, 10000, 2, 2, 30, 126, r.nextInt(sm));
		create(tests, 10000, 2, 2, 30, 230, r.nextInt(sm));
		create(tests, 10000, 2, 2, 30, 500, r.nextInt(sm));

		return tests;
	}

	public PreAggregateSDCZ_DDCTest(AMapToData m, AMapToData tm, ADictionary td, AOffset of, int nCol,
		double[] expected) {
		this.m = m;
		this.tm = tm;
		this.td = td;
		this.of = of;
		this.nCol = nCol;
		this.expected = expected;
	}

	@Test
	public void preAggregateSDCZ_DDC() {
		try {
			Dictionary ret = new Dictionary(new double[expected.length]);
			m.preAggregateSDCZ_DDC(tm, td, of, ret, nCol);
			compare(ret.getValues(), expected, 0.000001);
		}
		catch(Exception e) {
			e.printStackTrace();
			fail(this.toString());
		}
	}

	private final void compare(double[] res, double[] exp, double eps) {
		assertTrue(res.length == exp.length);
		for(int i = 0; i < res.length; i++)
			if(Math.abs(res[i] - exp[i]) >= eps)
				fail("not equivalent preaggregate with " + m.getClass().getSimpleName() + " "
					+ tm.getClass().getSimpleName() + "\n" + m + "\n" + tm + "\n" + td + "\n\n" + " res: "
					+ Arrays.toString(res) + "\n exp:" + Arrays.toString(exp));
	}

	private static void create(ArrayList<Object[]> tests, int nRows, int nUnique1, int nUnique2, int nCol, int offRange,
		int seed) {
		final Random r = new Random(seed);

		final AOffset of = MappingTestUtil.createRandomOffset(offRange, nRows, r);
		final AMapToData m = MappingTestUtil.createRandomMap(of.getSize(), nUnique1, r);
		final AMapToData tm = MappingTestUtil.createRandomMap(nRows, nUnique2, r);

		double[] dv = new double[nUnique2 * nCol];
		ADictionary td = new Dictionary(dv);

		for(int i = 0; i < dv.length; i++)
			dv[i] = r.nextDouble();

		double[] exp = new double[nUnique1 * nCol];

		try {
			// use implementation to get baseline.
			m.preAggregateSDCZ_DDC(tm, td, of, new Dictionary(exp), nCol);
			createAllPermutations(tests, m, tm, of, nUnique1, nUnique2, td, exp, nCol);
		}
		catch(Exception e) {
			e.printStackTrace();
			fail("Failed construction\n" + tm + "\n" + td + "\n" + of + "\n" + m);
		}
	}

	private static void createAllPermutations(ArrayList<Object[]> tests, AMapToData m, AMapToData tm, AOffset of,
		int nUnique1, int nUnique2, ADictionary td, double[] exp, int nCol) {

		AMapToData[] ml = MappingTestUtil.getAllHigherVersions(m);
		AMapToData[] tml = MappingTestUtil.getAllHigherVersions(tm);
		createFromList(tests, td, of, nCol, exp, ml, tml);
	}

	private static void createFromList(ArrayList<Object[]> tests, ADictionary td, AOffset of, int nCol, double[] exp,
		AMapToData[] ml, AMapToData[] tml) {
		for(AMapToData m : ml)
			for(AMapToData tm : tml)
				tests.add(new Object[] {m, tm, td, of, nCol, exp});

	}
}
