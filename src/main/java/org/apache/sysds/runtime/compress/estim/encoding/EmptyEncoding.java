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

package org.apache.sysds.runtime.compress.estim.encoding;

import org.apache.sysds.runtime.compress.estim.EstimationFactors;

/** Empty encoding for cases where the entire group of columns is zero */
public class EmptyEncoding implements IEncode {

	/** always a empty int array */
	private static final int[] counts = new int[] {};

	// empty constructor
	public EmptyEncoding() {
	}

	@Override
	public IEncode combine(IEncode e) {
		return e;
	}

	@Override
	public int getUnique() {
		return 1;
	}

	@Override
	public int size() {
		return 0;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(this.getClass().getSimpleName());
		return sb.toString();
	}

	@Override
	public EstimationFactors extractFacts(int[] cols, int nRows, double tupleSparsity, double matrixSparsity) {
		return new EstimationFactors(cols.length, 0, 0, nRows, counts, 0, nRows, false, true, 0, 0);
	}
}
