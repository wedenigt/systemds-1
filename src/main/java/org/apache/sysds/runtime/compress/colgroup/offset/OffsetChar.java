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
package org.apache.sysds.runtime.compress.colgroup.offset;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.sysds.utils.MemoryEstimates;

public class OffsetChar extends AOffset {

	private static final long serialVersionUID = -1192266421395964882L;
	private static final int maxV = (int) Character.MAX_VALUE;

	private final char[] offsets;
	private final int offsetToFirst;
	private final int offsetToLast;
	private final boolean noZero;

	public OffsetChar(int[] indexes) {
		this(indexes, 0, indexes.length);
	}

	public OffsetChar(int[] indexes, int apos, int alen) {
		int endSize = 0;
		offsetToFirst = indexes[apos];
		offsetToLast = indexes[alen - 1];
		int ov = offsetToFirst;
		for(int i = apos + 1; i < alen; i++) {
			final int nv = indexes[i];
			endSize += 1 + (nv - ov - 1) / maxV;
			ov = nv;
		}
		offsets = new char[endSize];
		ov = offsetToFirst;
		int p = 0;

		for(int i = apos + 1; i < alen; i++) {
			final int nv = indexes[i];
			final int offsetSize = (nv - ov);
			final int div = offsetSize / maxV;
			final int mod = offsetSize % maxV;
			if(mod == 0) {
				p += div - 1; // skip values
				offsets[p++] = (char) maxV;
			}
			else {
				p += div; // skip values
				offsets[p++] = (char) (mod);
			}

			ov = nv;
		}
		this.noZero = getNoZero();
	}

	private OffsetChar(char[] offsets, int offsetToFirst, int offsetToLast) {
		this.offsets = offsets;
		this.offsetToFirst = offsetToFirst;
		this.offsetToLast = offsetToLast;
		this.noZero = getNoZero();
	}

	private boolean getNoZero() {
		boolean noZero = true;
		for(char b : offsets)
			if(b == 0) {
				noZero = false;
				break;
			}
		return noZero;
	}

	@Override
	public AIterator getIterator() {
		if(noZero)
			return new IterateCharOffsetNoZero();
		else
			return new IterateCharOffset();
	}

	@Override
	public AOffsetIterator getOffsetIterator() {
		if(noZero)
			return new OffsetCharIteratorNoZero();
		else
			return new OffsetCharIterator();
	}

	@Override
	public void write(DataOutput out) throws IOException {
		out.writeByte(OffsetFactory.OFF_TYPE.CHAR.ordinal());
		out.writeInt(offsetToFirst);
		out.writeInt(offsets.length);
		out.writeInt(offsetToLast);
		for(char o : offsets)
			out.writeChar(o);
	}

	@Override
	public long getInMemorySize() {
		return estimateInMemorySize(offsets.length);
	}

	public static long estimateInMemorySize(int nOffs) {
		long size = 16 + 4 + 4 + 8; // object header plus int plus reference
		size += MemoryEstimates.charArrayCost(nOffs);
		return size;
	}

	@Override
	public long getExactSizeOnDisk() {
		return 1 + 4 + 4 + 4 + offsets.length * 2;
	}

	@Override
	public int getSize() {
		if(noZero)
			return offsets.length + 1;
		else {
			int size = 1;
			for(char b : offsets) {
				if(b != 0)
					size++;
			}
			return size;
		}
	}

	@Override
	public int getOffsetToFirst() {
		return offsetToFirst;
	}

	@Override
	public int getOffsetToLast() {
		return offsetToLast;
	}

	@Override
	public int getOffsetsLength() {
		return offsets.length;
	}

	public static OffsetChar readFields(DataInput in) throws IOException {
		final int offsetToFirst = in.readInt();
		final int offsetsLength = in.readInt();
		final int offsetToLast = in.readInt();
		final char[] offsets = new char[offsetsLength];

		for(int i = 0; i < offsetsLength; i++)
			offsets[i] = in.readChar();

		return new OffsetChar(offsets, offsetToFirst, offsetToLast);
	}

	private class IterateCharOffset extends AIterator {

		protected int index;
		protected int dataIndex;

		private IterateCharOffset() {
			super(offsetToFirst);
			index = 0;
			dataIndex = 0;
		}

		private IterateCharOffset(int index, int dataIndex, int offset) {
			super(offset);
			this.index = index;
			this.dataIndex = dataIndex;

		}

		@Override
		public int next() {
			char v = offsets[index];
			while(v == 0) {
				offset += maxV;
				index++;
				v = offsets[index];
			}
			offset += v;
			index++;
			dataIndex++;
			return offset;
		}

		@Override
		public int value() {
			return offset;
		}

		@Override
		public int skipTo(int idx) {
			while(offset < idx && index < offsets.length)
				next();
			return offset;
		}

		@Override
		public IterateCharOffset clone() {
			return new IterateCharOffset(index, dataIndex, offset);
		}

		@Override
		public int getDataIndex() {
			return dataIndex;
		}

		@Override
		public int getOffsetsIndex() {
			return index;
		}
	}

	private class IterateCharOffsetNoZero extends AIterator {

		protected int index;

		private IterateCharOffsetNoZero() {
			super(offsetToFirst);
			index = 0;
		}

		private IterateCharOffsetNoZero(int index, int offset) {
			super(offset);
			this.index = index;
		}

		@Override
		public int next() {
			char v = offsets[index];
			while(v == 0) {
				offset += maxV;
				index++;
				v = offsets[index];
			}
			offset += v;
			index++;
			return offset;
		}

		@Override
		public int value() {
			return offset;
		}

		@Override
		public int skipTo(int idx) {
			while(offset < idx && index < offsets.length)
				next();
			return offset;
		}

		@Override
		public IterateCharOffsetNoZero clone() {
			return new IterateCharOffsetNoZero(index, offset);
		}

		@Override
		public int getDataIndex() {
			return index;
		}

		@Override
		public int getOffsetsIndex() {
			return index;
		}
	}

	private class OffsetCharIterator extends AOffsetIterator {

		protected int index;

		private OffsetCharIterator() {
			super(offsetToFirst);
			index = 0;
		}

		@Override
		public int next() {
			char v = offsets[index];
			while(v == 0) {
				offset += maxV;
				index++;
				v = offsets[index];
			}
			index++;
			return offset += v;
		}
	}

	private class OffsetCharIteratorNoZero extends AOffsetIterator {

		protected int index;

		private OffsetCharIteratorNoZero() {
			super(offsetToFirst);
			index = 0;
		}

		@Override
		public int next() {
			char v = offsets[index];
			index++;
			return offset += v;
		}
	}
}
