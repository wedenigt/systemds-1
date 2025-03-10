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

package org.apache.sysds.test.component.compress.colgroup;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.EnumSet;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sysds.runtime.DMLRuntimeException;
import org.apache.sysds.runtime.compress.CompressionSettings;
import org.apache.sysds.runtime.compress.CompressionSettingsBuilder;
import org.apache.sysds.runtime.compress.colgroup.AColGroup;
import org.apache.sysds.runtime.compress.colgroup.AColGroup.CompressionType;
import org.apache.sysds.runtime.compress.colgroup.ColGroupFactory;
import org.apache.sysds.runtime.compress.estim.CompressedSizeEstimator;
import org.apache.sysds.runtime.compress.estim.CompressedSizeEstimatorExact;
import org.apache.sysds.runtime.compress.estim.CompressedSizeEstimatorFactory;
import org.apache.sysds.runtime.compress.estim.CompressedSizeInfo;
import org.apache.sysds.runtime.compress.estim.CompressedSizeInfoColGroup;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(value = Parameterized.class)
public abstract class JolEstimateTest {

	protected static final Log LOG = LogFactory.getLog(JolEstimateTest.class.getName());

	protected static final CompressionType ddc = CompressionType.DDC;
	protected static final CompressionType delta = CompressionType.DeltaDDC;
	protected static final CompressionType ole = CompressionType.OLE;
	protected static final CompressionType rle = CompressionType.RLE;
	protected static final CompressionType sdc = CompressionType.SDC;
	protected static final CompressionType unc = CompressionType.UNCOMPRESSED;
	private static final int seed = 7;

	private static final CompressionSettingsBuilder csb = new CompressionSettingsBuilder().setMinimumSampleSize(100)
		.setSeed(seed);

	private final int[] colIndexes;
	private final MatrixBlock mbt;

	public abstract CompressionType getCT();

	private final long actualSize;
	private final int actualNumberUnique;
	private final AColGroup cg;

	public JolEstimateTest(MatrixBlock mbt) {
		this.mbt = mbt;
		colIndexes = new int[mbt.getNumRows()];
		for(int x = 0; x < mbt.getNumRows(); x++)
			colIndexes[x] = x;

		mbt.recomputeNonZeros();
		mbt.examSparsity();
		try {
			CompressionSettings cs = new CompressionSettingsBuilder().setSamplingRatio(1.0)
				.setValidCompressions(EnumSet.of(getCT())).create();
			cs.transposed = true;

			final CompressedSizeInfoColGroup cgi = new CompressedSizeEstimatorExact(mbt, cs).getColGroupInfo(colIndexes);

			final CompressedSizeInfo csi = new CompressedSizeInfo(cgi);
			final List<AColGroup> groups = ColGroupFactory.compressColGroups(mbt, csi, cs, 1);

			if(groups.size() == 1) {
				cg = groups.get(0);
				actualSize = cg.estimateInMemorySize();
				actualNumberUnique = cg.getNumValues();
			}
			else {
				cg = null;
				actualSize = groups.stream().mapToLong(x -> x.estimateInMemorySize()).sum();
				actualNumberUnique = groups.stream().mapToInt(x -> x.getNumValues()).sum();
			}

		}
		catch(Exception e) {
			e.printStackTrace();
			throw new DMLRuntimeException("Failed construction : " + this.getClass().getSimpleName());
		}
	}

	@Test
	public void compressedSizeInfoEstimatorExact() {
		compressedSizeInfoEstimatorSample(1.0, 1.0);
	}

	@Test
	public void compressedSizeInfoEstimatorSample_90() {
		compressedSizeInfoEstimatorSample(0.9, 0.9);
	}

	@Test
	public void compressedSizeInfoEstimatorSample_50() {
		compressedSizeInfoEstimatorSample(0.5, 0.8);
	}

	@Test
	public void compressedSizeInfoEstimatorSample_20() {
		compressedSizeInfoEstimatorSample(0.2, 0.6);
	}

	@Test
	public void compressedSizeInfoEstimatorSample_10() {
		compressedSizeInfoEstimatorSample(0.1, 0.5);
	}

	// @Test
	// public void compressedSizeInfoEstimatorSample_5() {
	// compressedSizeInfoEstimatorSample(0.05, 0.5);
	// }

	// @Test
	// public void compressedSizeInfoEstimatorSample_1() {
	// compressedSizeInfoEstimatorSample(0.01, 0.4);
	// }

	@Test
	public void testToString() {
		// just to add a tests to verify that the to String method does not crash
		if(cg != null)
			cg.toString();
	}

	public void compressedSizeInfoEstimatorSample(double ratio, double tolerance) {
		if(cg == null)
			return;
		try {
			if(mbt.getNumColumns() > 10000)
				tolerance = tolerance * 0.95;
			final CompressionSettings cs = csb.setSamplingRatio(ratio).setMinimumSampleSize(10)
				.setValidCompressions(EnumSet.of(getCT())).create();
			cs.transposed = true;

			final int sampleSize = Math.max(10, (int) (mbt.getNumColumns() * ratio));
			final CompressedSizeEstimator est = CompressedSizeEstimatorFactory.createEstimator(mbt, cs, sampleSize, 1);

			// final int sampleSize = est.getSampleSize();
			final CompressedSizeInfoColGroup cInfo = est.getColGroupInfo(colIndexes);
			// LOG.error(cg);
			final int estimateNUniques = cInfo.getNumVals();
			final long estimateCSI = cInfo.getCompressionSize(cg.getCompType());
			final double minTolerance = actualSize * tolerance *
				(ratio < 1 && mbt.getSparsity() < 0.8 ? mbt.getSparsity() + 0.2 : 1);
			final double maxTolerance = actualSize / tolerance +
				(cg.getCompType() == CompressionType.SDC ? +8 * mbt.getNumRows() : 0);
			final boolean withinToleranceOnSize = minTolerance <= estimateCSI && estimateCSI <= maxTolerance;
			// LOG.error(cg);
			if(!withinToleranceOnSize) {
				final String rangeString = String.format("%.0f <= %d <= %.0f , Actual Size %d", minTolerance, estimateCSI,
					maxTolerance, actualSize);

				fail("CSI Sampled estimate size is not in tolerance range \n" + rangeString + "\nActual number uniques:"
					+ actualNumberUnique + " estimated Uniques: " + estimateNUniques + "\nSampleSize of total rows:: "
					+ sampleSize + " " + mbt.getNumColumns() + "\n" + cInfo + "\n" + mbt + "\n" + cg);
			}

		}
		catch(Exception e) {
			e.printStackTrace();
			assertTrue("Failed sample test " + getCT() + "", false);
		}
	}
}
