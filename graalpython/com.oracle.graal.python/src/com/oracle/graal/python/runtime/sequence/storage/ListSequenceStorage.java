/*
 * Copyright (c) 2017, 2018, Oracle and/or its affiliates.
 * Copyright (c) 2013, Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.oracle.graal.python.runtime.sequence.storage;

import java.util.Arrays;

import com.oracle.graal.python.builtins.objects.list.PList;
import com.oracle.graal.python.runtime.sequence.SequenceUtil;

public final class ListSequenceStorage extends TypedSequenceStorage {

    private PList[] values;
    private Class<?> kind;

    public ListSequenceStorage(SequenceStorage elementSample) {
        values = new PList[]{};
        if (elementSample instanceof ListSequenceStorage) {
            ListSequenceStorage list = (ListSequenceStorage) elementSample;
            this.kind = list.getKind();
        } else {
            this.kind = elementSample.getClass();
        }
    }

    public ListSequenceStorage(PList[] elements) {
        this(elements, elements[0].getSequenceStorage().getClass());
        if (kind == ListSequenceStorage.class)
            this.kind = ((ListSequenceStorage) elements[0].getSequenceStorage()).getKind();
    }

    public ListSequenceStorage(PList[] elements, Class<?> kind) {
        this.values = elements;
        capacity = values.length;
        length = elements.length;
        this.kind = kind;
    }

    public ListSequenceStorage(int capacity, Class<?> kind) {
        this.values = new PList[capacity];
        this.capacity = capacity;
        length = 0;
        this.kind = kind;
    }

    @Override
    protected void increaseCapacityExactWithCopy(int newCapacity) {
        values = Arrays.copyOf(values, newCapacity);
        capacity = values.length;
    }

    @Override
    protected void increaseCapacityExact(int newCapacity) {
        values = new PList[newCapacity];
        capacity = values.length;
    }

    @Override
    public SequenceStorage copy() {
        return new ListSequenceStorage(Arrays.copyOf(values, length), this.kind);
    }

    @Override
    public SequenceStorage createEmpty(int newCapacity) {
        return new ListSequenceStorage(newCapacity, this.kind);
    }

    public Class<?> getKind() {
        return kind;
    }

    @Override
    public Object[] getInternalArray() {
        /**
         * Have to box and copy.
         */
        Object[] boxed = new Object[length];

        for (int i = 0; i < length; i++) {
            boxed[i] = values[i];
        }

        return boxed;
    }

    public PList[] getInternalListArray() {
        return values;
    }

    @Override
    public Object getItemNormalized(int idx) {
        return getListItemNormalized(idx);
    }

    public PList getListItemNormalized(int idx) {
        return values[idx];
    }

    @Override
    public void setItemNormalized(int idx, Object value) throws SequenceStoreException {
        if (value instanceof PList) {
            setListItemNormalized(idx, (PList) value);
        } else {
            throw SequenceStoreException.INSTANCE;
        }
    }

    public void setListItemNormalized(int idx, PList value) {
        values[idx] = value;
    }

    @Override
    public void insertItem(int idx, Object value) throws SequenceStoreException {
        if (value instanceof PList) {
            insertListItem(idx, (PList) value);
        } else {
            throw SequenceStoreException.INSTANCE;
        }
    }

    public void insertListItem(int idx, PList value) {
        ensureCapacity(length + 1);

        // shifting tail to the right by one slot
        for (int i = values.length - 1; i > idx; i--) {
            values[i] = values[i - 1];
        }

        values[idx] = value;
        length++;
    }

    @Override
    public void copyItem(int idxTo, int idxFrom) {
        values[idxTo] = values[idxFrom];
    }

    @Override
    public SequenceStorage getSliceInBound(int start, int stop, int step, int sliceLength) {
        PList[] newArray = new PList[sliceLength];

        if (step == 1) {
            System.arraycopy(values, start, newArray, 0, sliceLength);
            return new ListSequenceStorage(newArray, this.kind);
        }

        for (int i = start, j = 0; j < sliceLength; i += step, j++) {
            newArray[j] = values[i];
        }

        return new ListSequenceStorage(newArray, this.kind);
    }

    @Override
    public void setSliceInBound(int start, int stop, int step, SequenceStorage sequence) throws SequenceStoreException {
        if (sequence instanceof ListSequenceStorage) {
            setListSliceInBound(start, stop, step, (ListSequenceStorage) sequence);
        } else {
            throw new SequenceStoreException();
        }
    }

    public void setListSliceInBound(int start, int stop, int step, ListSequenceStorage sequence) {
        int otherLength = sequence.length();

        // range is the whole sequence?
        if (start == 0 && stop == length) {
            values = Arrays.copyOf(sequence.values, otherLength);
            length = otherLength;
            minimizeCapacity();
            return;
        }

        ensureCapacity(stop);

        for (int i = start, j = 0; i < stop; i += step, j++) {
            values[i] = sequence.values[j];
        }

        length = length > stop ? length : stop;
    }

    @Override
    public void delSlice(int startParam, int stopParam, int stepParam) {
        int start = startParam;
        int stop = stopParam;
        int step = stepParam;
        if ((stop == SequenceUtil.MISSING_INDEX || stop >= length) && step == 1) {
            length = start;
        } else if ((start == 0 && stop >= length) && step == 1) {
            length = 0;
        } else {
            int decraseLen; // how much will be the result array shorter
            int index;  // index of the "old" array
            if (step < 0) {
                // For the simplicity of algorithm, then start and stop are swapped.
                // The start index has to recalculated according the step, because
                // the algorithm bellow removes the start itema and then start + step ....
                step = Math.abs(step);
                stop++;
                int tmpStart = stop + ((start - stop) % step);
                stop = start + 1;
                start = tmpStart;
            }
            int arrayIndex = start; // pointer to the "new" form of array
            if (step == 1) {
                // this is easy, just remove the part of array
                decraseLen = stop - start;
                index = start + decraseLen;
            } else {
                int nextStep = index = start; // nextStep is a pointer to the next removed item
                decraseLen = (stop - start - 1) / step + 1;
                for (; index < stop && nextStep < stop; index++) {
                    if (nextStep == index) {
                        nextStep += step;
                    } else {
                        values[arrayIndex++] = values[index];
                    }
                }
            }
            if (decraseLen > 0) {
                // shift all other items in array behind the last change
                for (; index < length; arrayIndex++, index++) {
                    values[arrayIndex] = values[index];
                }
                // change the result length
                // TODO Shouldn't we realocate the array, if the chane is big?
                // Then unnecessary big array is kept in the memory.
                length = length - decraseLen;
            }
        }
    }

    @Override
    public void delItemInBound(int idx) {
        if (values.length - 1 == idx) {
            popList();
        } else {
            popInBound(idx);
        }
    }

    @Override
    public Object popInBound(int idx) {
        PList pop = values[idx];

        for (int i = idx; i < values.length - 1; i++) {
            values[i] = values[i + 1];
        }

        length--;
        return pop;
    }

    public PList popList() {
        PList pop = values[capacity - 1];
        length--;
        return pop;
    }

    @Override
    public int index(Object value) {
        if (value instanceof PList) {
            return indexOfList((PList) value);
        } else {
            return super.index(value);
        }

    }

    public int indexOfList(PList value) {
        for (int i = 0; i < length; i++) {
            if (values[i] == value) {
                return i;
            }
        }

        return -1;
    }

    @Override
    public void append(Object value) throws SequenceStoreException {
        if (value instanceof PList) {
            SequenceStorage list = ((PList) value).getSequenceStorage();
            if (list instanceof ListSequenceStorage && ((ListSequenceStorage) list).getKind() == kind)
                appendList((PList) value);
            else if (list.getClass() == kind)
                appendList((PList) value);
            else
                throw SequenceStoreException.INSTANCE;
        } else
            throw SequenceStoreException.INSTANCE;

    }

    public void appendList(PList value) {
        ensureCapacity(length + 1);
        values[length] = value;
        length++;
    }

    @Override
    public void extend(SequenceStorage other) throws SequenceStoreException {
        if (other instanceof ListSequenceStorage) {
            extendWithListStorage((ListSequenceStorage) other);
        } else {
            throw SequenceStoreException.INSTANCE;
        }
    }

    public void extendWithListStorage(ListSequenceStorage other) {
        int extendedLength = length + other.length();
        ensureCapacity(extendedLength);
        PList[] otherValues = other.values;

        for (int i = length, j = 0; i < extendedLength; i++, j++) {
            values[i] = otherValues[j];
        }

        length = extendedLength;
    }

    @Override
    public void reverse() {
        if (length > 0) {
            int head = 0;
            int tail = length - 1;
            int middle = (length - 1) / 2;

            for (; head <= middle; head++, tail--) {
                PList temp = values[head];
                values[head] = values[tail];
                values[tail] = temp;
            }
        }
    }

    @Override
    public void sort() {
        // TODO: need to be tested
        Object[] copy = Arrays.copyOf(values, length);
        Arrays.sort(copy);
        values = (PList[]) copy;
        minimizeCapacity();
    }

    @Override
    public Object getIndicativeValue() {
        return length > 0 ? values[0] : null;
    }

    @Override
    public boolean equals(SequenceStorage other) {
        // TODO: equal algorithm might need more tests
        if (other.length() != length() || !(other instanceof ListSequenceStorage)) {
            return false;
        }

        PList[] otherArray = ((ListSequenceStorage) other).getInternalListArray();
        for (int i = 0; i < length(); i++) {
            if (values[i] != otherArray[i]) {
                return false;
            }
        }

        return true;
    }

}
