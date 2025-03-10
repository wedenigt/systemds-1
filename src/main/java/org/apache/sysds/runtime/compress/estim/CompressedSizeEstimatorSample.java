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

package org.apache.sysds.runtime.compress.estim;

import java.util.Arrays;
import java.util.Random;

import org.apache.commons.lang.NotImplementedException;
import org.apache.sysds.runtime.compress.CompressionSettings;
import org.apache.sysds.runtime.compress.estim.encoding.IEncode;
import org.apache.sysds.runtime.compress.estim.sample.SampleEstimatorFactory;
import org.apache.sysds.runtime.controlprogram.parfor.stat.Timing;
import org.apache.sysds.runtime.data.DenseBlock;
import org.apache.sysds.runtime.data.SparseBlock;
import org.apache.sysds.runtime.data.SparseBlockMCSR;
import org.apache.sysds.runtime.data.SparseRow;
import org.apache.sysds.runtime.matrix.data.LibMatrixReorg;
import org.apache.sysds.runtime.matrix.data.MatrixBlock;

public class CompressedSizeEstimatorSample extends CompressedSizeEstimator {

	private final MatrixBlock _sample;
	private final int _k;
	private final int _sampleSize;
	/** Boolean specifying if the sample is in transposed format. */
	private boolean _transposed;

	/**
	 * CompressedSizeEstimatorSample, samples from the input data and estimates the size of the compressed matrix.
	 * 
	 * @param data       The input data toSample from
	 * @param cs         The Settings used for the sampling, and compression, contains information such as seed.
	 * @param sampleSize The size to sample from the data.
	 * @param k          The parallelization degree allowed.
	 */
	public CompressedSizeEstimatorSample(MatrixBlock data, CompressionSettings cs, int sampleSize, int k) {
		super(data, cs);
		_k = k;
		_sampleSize = sampleSize;
		_transposed = _cs.transposed;
		if(LOG.isDebugEnabled()) {
			Timing time = new Timing(true);
			_sample = sampleData(sampleSize);
			LOG.debug("Sampling time: " + time.stop());
		}
		else
			_sample = sampleData(sampleSize);

	}

	@Override
	public CompressedSizeInfoColGroup getColGroupInfo(int[] colIndexes, int estimate, int maxDistinct) {
		if(nnzCols != null && colIndexes.length == 1 && nnzCols[colIndexes[0]] == 0)
			return new CompressedSizeInfoColGroup(colIndexes, getNumRows());

		final IEncode map = IEncode.createFromMatrixBlock(_sample, _transposed, colIndexes);
		return extractInfo(map, colIndexes, maxDistinct);
	}

	@Override
	public CompressedSizeInfoColGroup getDeltaColGroupInfo(int[] colIndexes, int estimate, int maxDistinct) {
		// Don't use sample when doing estimation of delta encoding, instead we read from the start of the matrix until
		// sample size. This guarantees that the delta values are actually represented in the full compression
		final IEncode map = IEncode.createFromMatrixBlockDelta(_data, _transposed, colIndexes, _sampleSize);
		return extractInfo(map, colIndexes, maxDistinct);
	}

	@Override
	protected int worstCaseUpperBound(int[] columns) {
		if(getNumColumns() == columns.length)
			return Math.min(getNumRows(), (int) _data.getNonZeros());
		return getNumRows();
	}

	@Override
	protected CompressedSizeInfoColGroup combine(int[] combinedColumns, CompressedSizeInfoColGroup g1,
		CompressedSizeInfoColGroup g2, int maxDistinct) {
		final IEncode map = g1.getMap().combine(g2.getMap());
		return extractInfo(map, combinedColumns, maxDistinct);
	}

	private CompressedSizeInfoColGroup extractInfo(IEncode map, int[] colIndexes, int maxDistinct) {
		final EstimationFactors sampleFacts = map.extractFacts(colIndexes, _sampleSize, _data.getSparsity(),
			_data.getSparsity());
		final EstimationFactors em = scaleFactors(sampleFacts, colIndexes, maxDistinct);
		return new CompressedSizeInfoColGroup(colIndexes, em, _cs.validCompressions, map);
	}

	private EstimationFactors scaleFactors(EstimationFactors sampleFacts, int[] colIndexes, int maxDistinct) {
		final int numRows = getNumRows();

		final double scalingFactor = (double) numRows / _sampleSize;

		final long nnz = calculateNNZ(colIndexes, scalingFactor);
		final int numOffs = calculateOffs(sampleFacts, numRows, scalingFactor, colIndexes, nnz);
		final int estDistinct = distinctCountScale(sampleFacts, numOffs, maxDistinct);

		// calculate the largest instance count.
		final int maxLargestInstanceCount = numRows - estDistinct + 1;
		final int scaledLargestInstanceCount = (int) Math.floor(sampleFacts.largestOff * scalingFactor);
		final int largestInstanceCount = Math.min(maxLargestInstanceCount, scaledLargestInstanceCount);

		final double overallSparsity = calculateSparsity(colIndexes, nnz, scalingFactor, sampleFacts.overAllSparsity);

		// For safety add 10 percent more tuple sparsity to estimate since it can have a big impact
		// on workload
		final double tupleSparsity = Math.min(overallSparsity + 0.1, 1.0);

		return new EstimationFactors(colIndexes.length, estDistinct, numOffs, largestInstanceCount,
			sampleFacts.frequencies, sampleFacts.numSingle, numRows, sampleFacts.lossy, sampleFacts.zeroIsMostFrequent,
			overallSparsity, tupleSparsity);

	}

	private int distinctCountScale(EstimationFactors sampleFacts, int numOffs, int maxDistinct) {
		// the frequencies of non empty entries.
		final int[] freq = sampleFacts.frequencies;
		if(freq == null || freq.length == 0)
			return numOffs > 0 ? 1 : 0;
		// sampled size is smaller than actual if there was empty rows.
		// and the more we can reduce this value the more accurate the estimation will become.
		final int sampledSize = sampleFacts.numOffs;
		final int est = SampleEstimatorFactory.distinctCount(freq, numOffs, sampledSize, _cs.estimationType);
		// Bound the estimate with the maxDistinct.
		return Math.min(est, maxDistinct);
	}

	private int calculateOffs(EstimationFactors sampleFacts, int numRows, double scalingFactor, int[] colIndexes,
		long nnz) {
		final int numCols = getNumColumns();
		if(numCols == 1 || (nnzCols != null && colIndexes.length == 1))
			return (int) nnz;
		else {
			final int emptyTuples = sampleFacts.numRows - sampleFacts.numOffs;
			return numRows - (int) Math.floor(emptyTuples * scalingFactor);
		}
	}

	private double calculateSparsity(int[] colIndexes, long nnz, double scalingFactor, double sampleValue) {
		if(colIndexes.length == getNumColumns())
			return _data.getSparsity();
		else if(nnzCols != null || (_cs.transposed && _data.isInSparseFormat()) ||
			(_transposed && _sample.isInSparseFormat()))
			return (double) nnz / (getNumRows() * colIndexes.length);
		else if(_sample.isEmpty())
			// Make a semi safe bet of using the data input sparsity if the sample was empty.
			return _data.getSparsity();
		else
			return sampleValue;
	}

	private long calculateNNZ(int[] colIndexes, double scalingFactor) {
		if(colIndexes.length == getNumColumns())
			return _data.getNonZeros();
		else if(_cs.transposed && _data.isInSparseFormat()) {
			// Use exact if possible
			long nnzCount = 0;
			SparseBlock sb = _data.getSparseBlock();
			for(int i = 0; i < colIndexes.length; i++)
				nnzCount += sb.get(i).size();
			return nnzCount;
		}
		else if(nnzCols != null) {
			long nnz = 0;
			for(int i = 0; i < colIndexes.length; i++)
				nnz += nnzCols[colIndexes[i]];
			return nnz;
		}
		else if(_sample.isEmpty())
			return 0;
		else if(_transposed && _sample.isInSparseFormat()) {
			// Fallback to the sample if original is not transposed
			long nnzCount = 0;
			SparseBlock sb = _sample.getSparseBlock();
			for(int i = 0; i < colIndexes.length; i++)
				if(!sb.isEmpty(i))
					nnzCount += sb.get(i).size() * scalingFactor;

			// add one to make sure that Uncompressed columns are considered as containing at least one value.
			if(nnzCount == 0)
				nnzCount += colIndexes.length;
			return nnzCount;
		}
		else
			// if all others aren't available use the samples value.
			return _sample.getNonZeros();
	}

	private static int[] getSortedSample(int range, int sampleSize, long seed, int k) {
		// set meta data and allocate dense block
		final int[] a = new int[sampleSize];

		Random r = new Random(seed);
		// reservoir sampling
		for(int i = 0; i < sampleSize; i++)
			a[i] = i;

		for(int i = sampleSize; i < range; i++)
			if(r.nextInt(i) < sampleSize)
				a[r.nextInt(sampleSize)] = i;

		if(range / 100 < sampleSize) {
			// randomize the sample (Algorithm P from Knuth's ACP)
			// needed especially when the difference between range and sampleSize is small)
			for(int i = 0; i < sampleSize - 1; i++) {
				// generate index in i <= j < sampleSize
				int j = r.nextInt(sampleSize - i) + i;
				// swap i^th and j^th entry
				int tmp = a[i];
				a[i] = a[j];
				a[j] = tmp;
			}
		}

		// Sort the sample
		if(k > 1)
			Arrays.parallelSort(a);
		else
			Arrays.sort(a);
		return a;
	}

	private MatrixBlock sampleData(int sampleSize) {

		final int[] sampleRows = CompressedSizeEstimatorSample.getSortedSample(getNumRows(), sampleSize, _cs.seed, _k);
		MatrixBlock sampledMatrixBlock;
		if(!_cs.transposed) {
			if(_data.isInSparseFormat())
				sampledMatrixBlock = sparseNotTransposedSamplePath(sampleRows);
			else
				sampledMatrixBlock = denseSamplePath(sampleRows);
		}
		else
			sampledMatrixBlock = defaultSlowSamplingPath(sampleRows);

		return sampledMatrixBlock;
	}

	private MatrixBlock sparseNotTransposedSamplePath(int[] sampleRows) {
		MatrixBlock res = new MatrixBlock(sampleRows.length, _data.getNumColumns(), true);
		SparseRow[] rows = new SparseRow[sampleRows.length];
		SparseBlock in = _data.getSparseBlock();
		for(int i = 0; i < sampleRows.length; i++)
			rows[i] = in.get(sampleRows[i]);

		res.setSparseBlock(new SparseBlockMCSR(rows, false));
		res.recomputeNonZeros();
		_transposed = true;
		res = LibMatrixReorg.transposeInPlace(res, _k);

		return res;
	}

	private MatrixBlock defaultSlowSamplingPath(int[] sampleRows) {
		MatrixBlock select = (_cs.transposed) ? new MatrixBlock(_data.getNumColumns(), 1,
			false) : new MatrixBlock(_data.getNumRows(), 1, false);
		for(int i = 0; i < sampleRows.length; i++)
			select.appendValue(sampleRows[i], 0, 1);
		MatrixBlock ret = _data.removeEmptyOperations(new MatrixBlock(), !_cs.transposed, true, select);
		return ret;
	}

	private MatrixBlock denseSamplePath(int[] sampleRows) {
		final int sampleSize = sampleRows.length;
		final double sampleRatio = _cs.transposed ? (double) _data.getNumColumns() /
			sampleSize : (double) _data.getNumRows() / sampleSize;
		final long inputNonZeros = _data.getNonZeros();
		final long estimatedNonZerosInSample = (long) Math.ceil((double) inputNonZeros / sampleRatio);
		final int resRows = _cs.transposed ? _data.getNumRows() : _data.getNumColumns();
		final long nCellsInSample = (long) sampleSize * resRows;
		final boolean shouldBeSparseSample = 0.4 > (double) estimatedNonZerosInSample / nCellsInSample;
		MatrixBlock res = new MatrixBlock(resRows, sampleSize, shouldBeSparseSample);
		res.allocateBlock();

		final DenseBlock inb = _data.getDenseBlock();
		if(res.isInSparseFormat()) {
			final SparseBlock resb = res.getSparseBlock();
			if(resb instanceof SparseBlockMCSR) {
				final SparseBlockMCSR resbmcsr = (SparseBlockMCSR) resb;
				final int estimatedNrDoublesEachRow = (int) Math.max(4, Math.ceil(estimatedNonZerosInSample / sampleSize));
				for(int col = 0; col < resRows; col++)
					resbmcsr.allocate(col, estimatedNrDoublesEachRow);

				for(int row = 0; row < sampleSize; row++) {
					final int inRow = sampleRows[row];
					final double[] inBlockV = inb.values(inRow);
					final int offIn = inb.pos(inRow);
					for(int col = 0; col < resRows; col++) {
						final SparseRow srow = resbmcsr.get(col);
						srow.append(row, inBlockV[offIn + col]);
					}
				}
			}
			else
				throw new NotImplementedException(
					"Not Implemented support for dense sample into sparse: " + resb.getClass().getSimpleName());

		}
		else {
			final DenseBlock resb = res.getDenseBlock();
			for(int row = 0; row < sampleSize; row++) {
				final int inRow = sampleRows[row];
				final double[] inBlockV = inb.values(inRow);
				final int offIn = inb.pos(inRow);
				for(int col = 0; col < resRows; col++) {
					final double[] blockV = resb.values(col);
					blockV[col * sampleSize + row] = inBlockV[offIn + col];
				}
			}
		}
		res.setNonZeros(estimatedNonZerosInSample);
		_transposed = true;

		return res;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(super.toString());
		sb.append(" sampleSize: ");
		sb.append(_sampleSize);
		sb.append(" transposed: ");
		sb.append(_transposed);
		return sb.toString();
	}
}
