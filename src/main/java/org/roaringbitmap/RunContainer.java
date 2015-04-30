/*
 * (c) Daniel Lemire, Owen Kaser, Samy Chambi, Jon Alvarado, Rory Graves, Björn Sperber
 * Licensed under the Apache License, Version 2.0.
 */
package org.roaringbitmap;

import java.io.*;
import java.util.Arrays;
import java.util.Iterator;

/**
 * This container takes the form of runs of consecutive values (effectively,
 * run-length encoding).
 */
public class RunContainer extends Container implements Cloneable, Serializable {
    private static final int DEFAULT_INIT_SIZE = 4;
    private short[] valueslength;// we interleave values and lengths, so 
    // that if you have the values 11,12,13,14,15, you store that as 11,4 where 4 means that beyond 11 itself, there are
    // 4 contiguous values that follows.
    // Other example: e.g., 1, 10, 20,0, 31,2 would be a concise representation of  1, 2, ..., 11, 20, 31, 32, 33

    int nbrruns = 0;// how many runs, this number should fit in 16 bits.

    
    private RunContainer(int nbrruns, short[] valueslength) {
        this.nbrruns = nbrruns;
        this.valueslength = Arrays.copyOf(valueslength, valueslength.length);
    }


    private void increaseCapacity() {
        int newCapacity = (valueslength.length == 0) ? DEFAULT_INIT_SIZE : valueslength.length < 64 ? valueslength.length * 2
                : valueslength.length < 1024 ? valueslength.length * 3 / 2
                : valueslength.length * 5 / 4;
        short[] nv = new short[newCapacity];
        System.arraycopy(valueslength, 0, nv, 0, 2 * nbrruns);
        valueslength = nv;
    }
    
    /**
     * Create a container with default capacity
     */
    public RunContainer() {
        this(DEFAULT_INIT_SIZE);
    }

    /**
     * Create an array container with specified capacity
     *
     * @param capacity The capacity of the container
     */
    public RunContainer(final int capacity) {
        valueslength = new short[2 * capacity];
    }

    
    @Override
    public Iterator<Short> iterator() {
        final ShortIterator i  = getShortIterator();
        return new Iterator<Short>() {

            @Override
            public boolean hasNext() {
               return  i.hasNext();
            }

            @Override
            public Short next() {
                return i.next();
            }

            @Override
            public void remove() {
                i.remove();
            }
        };

    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        serialize(out);
        
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException,
            ClassNotFoundException {
        deserialize(in);
    }

    @Override
    public Container flip(short x) {
        if(this.contains(x))
            return this.remove(x);
        else return this.add(x);
    }

    @Override
    public Container add(short k) {
        int index = unsignedInterleavedBinarySearch(valueslength, 0, nbrruns, k);
        if(index >= 0) return this;// already there
        index = - index - 2;// points to preceding value, possibly -1
        if(index >= 0) {// possible match
            int offset = Util.toIntUnsigned(k) - Util.toIntUnsigned(getValue(index));
            int le =     Util.toIntUnsigned(getLength(index)); 
            if(offset <= le) return this;
            if(offset == le + 1) {
                // we may need to fuse
                if(index + 1 < nbrruns) {
                    if(Util.toIntUnsigned(getValue(index + 1))  == Util.toIntUnsigned(k) + 1) {
                        // indeed fusion is needed
                        setLength(index, (short) (getValue(index + 1) + getLength(index + 1) - getValue(index)));
                        recoverRoomAtIndex(index + 1);
                        return this;
                    }
                }
                incrementLength(index);
                return this;
            }
            if(index + 1 < nbrruns) {
                // we may need to fuse
                if(Util.toIntUnsigned(getValue(index + 1))  == Util.toIntUnsigned(k) + 1) {
                    // indeed fusion is needed
                    setValue(index+1, k);
                    setLength(index+1, (short) (getLength(index + 1) + 1));
                    return this;
                }
            }
        }
        if(index == -1) {
            // we may need to extend the first run
            if(0 < nbrruns) {
                if(getValue(0)  == k + 1) {
                    incrementLength(0);
                    decrementValue(0);
                    return this;
                }
            }
        }
        makeRoomAtIndex(index + 1);
        setValue(index + 1, k);
        setLength(index + 1, (short) 0);
        return this;
    }

    @Override
    public Container add(int begin, int end) {
        RunContainer rc = (RunContainer) clone();
        return rc.iadd(begin, end);
    }

    @Override
    public Container and(ArrayContainer x) {
        ArrayContainer ac = new ArrayContainer(x.cardinality);
        int rlepos = 0;
        int arraypos = 0;
        while((arraypos < x.cardinality) && (rlepos < this.nbrruns)) {
            if(Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) < Util.toIntUnsigned(x.content[arraypos])) {
                ++rlepos;
            } else if(Util.toIntUnsigned(this.getValue(rlepos)) > Util.toIntUnsigned(x.content[arraypos]))  {
                arraypos = Util.advanceUntil(x.content,arraypos,x.cardinality,this.getValue(rlepos));
            } else {
                ac.content[ac.cardinality ++ ] = x.content[arraypos++];
            }
        }
        return ac;
    }
    

    @Override
    public Container and(BitmapContainer x) {
        BitmapContainer answer = x.clone();
        int start = 0;
        for(int rlepos = 0; rlepos < this.nbrruns; ++rlepos ) {
            int end = Util.toIntUnsigned(this.getValue(rlepos));
            Util.resetBitmapRange(x.bitmap, start, end);
            start = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
        }
        Util.resetBitmapRange(x.bitmap, start, Util.maxLowBitAsInteger() + 1);
        answer.computeCardinality();
        if(answer.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE)
            return answer;
        else return answer.toArrayContainer();
    }

    @Override
    public Container andNot(ArrayContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container andNot(BitmapContainer x) {
        BitmapContainer answer = x.clone();
        for(int rlepos = 0; rlepos < this.nbrruns; ++rlepos ) {
            int start = Util.toIntUnsigned(this.getValue(rlepos));
            int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
            Util.resetBitmapRange(x.bitmap, start, end);
        }
        answer.computeCardinality();
        if(answer.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE)
            return answer;
        else return answer.toArrayContainer();
    }

    @Override
    public void clear() {
        nbrruns = 0;
    }

    @Override
    public Container clone() {
        return new RunContainer(nbrruns, valueslength);
    }

    @Override
    public boolean contains(short x) {
        int index = unsignedInterleavedBinarySearch(valueslength, 0, nbrruns, x);
        if(index >= 0) return true;
        index = - index - 2; // points to preceding value, possibly -1
        if (index != -1)  {// possible match
            int offset = Util.toIntUnsigned(x) - Util.toIntUnsigned(getValue(index));
            int le =     Util.toIntUnsigned(getLength(index)); 
            if(offset <= le) return true;
        }
        return false;
    }

    @Override
    public void deserialize(DataInput in) throws IOException {
        nbrruns = Short.reverseBytes(in.readShort());
        if(valueslength.length < 2 * nbrruns)
            valueslength = new short[2 * nbrruns];
        for (int k = 0; k < 2 * nbrruns; ++k) {
            this.valueslength[k] = Short.reverseBytes(in.readShort());
        }
    }

    @Override
    public void fillLeastSignificant16bits(int[] x, int i, int mask) {
        int pos = 0;
        for (int k = 0; k < this.nbrruns; ++k) {
            for(int le = 0; le <= Util.toIntUnsigned(this.getLength(k)); ++le) {
              x[k + pos] = (Util.toIntUnsigned(this.getValue(k)) + le) | mask;
              pos++;
            }
        }
    }

    @Override
    protected int getArraySizeInBytes() {
        return 2 + 4 * this.nbrruns;
    }

    @Override
    public int getCardinality() {
        int sum = 0;
        for(int k = 0; k < nbrruns; ++k)
            sum = sum + Util.toIntUnsigned(getLength(k)) + 1;
        return sum;
    }

    @Override
    public ShortIterator getShortIterator() {
        return new RunContainerShortIterator(this);
    }

    @Override
    public ShortIterator getReverseShortIterator() {
        return new ReverseRunContainerShortIterator(this);
    }

    @Override
    public int getSizeInBytes() {
        return this.nbrruns * 4 + 4;
    }

    @Override
    public Container iand(ArrayContainer x) {
        return and(x);
    }

    @Override
    public Container iand(BitmapContainer x) {
        return and(x);
    }

    @Override
    public Container iandNot(ArrayContainer x) {
        return andNot(x);
    }

    @Override
    public Container iandNot(BitmapContainer x) {
        return andNot(x);
    }

    @Override
    public Container inot(int rangeStart, int rangeEnd) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container ior(ArrayContainer x) {
        return or(x);
    }

    @Override
    public Container ior(BitmapContainer x) {
        return or(x);
    }

    @Override
    public Container ixor(ArrayContainer x) {
        return xor(x);
    }

    @Override
    public Container ixor(BitmapContainer x) {
        return xor(x);
    }

    @Override
    public Container not(int rangeStart, int rangeEnd) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container or(ArrayContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container or(BitmapContainer x) {
        BitmapContainer answer = x.clone();
        for(int rlepos = 0; rlepos < this.nbrruns; ++rlepos ) {
            int start = Util.toIntUnsigned(this.getValue(rlepos));
            int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
            Util.setBitmapRange(x.bitmap, start, end);
        }
        answer.computeCardinality();
        return answer;
    }

    @Override
    public Container remove(short x) {
        int index = unsignedInterleavedBinarySearch(valueslength, 0, nbrruns, x);
        if(index >= 0) {
            int le =  Util.toIntUnsigned(getLength(index));
            if(le == 0) {
                recoverRoomAtIndex(index);
            } else {
                incrementValue(index);
                decrementLength(index);
            }
            return this;// already there
        }
        index = - index - 2;// points to preceding value, possibly -1
        if((index >= 0) && (index < nbrruns)) {// possible match
            int offset = Util.toIntUnsigned(x) - Util.toIntUnsigned(getValue(index));
            int le =     Util.toIntUnsigned(getLength(index)); 
            if(offset < le) {
                // need to break in two
                this.setLength(index, (short) (offset - 1));
                // need to insert
                int newvalue = Util.toIntUnsigned(x) + 1;
                int newlength = le - offset - 1;
                makeRoomAtIndex(index+1);
                this.setValue(index+1, (short) newvalue);
                this.setLength(index+1, (short) newlength);
            } else if(offset == le) {
                decrementLength(index);
            }
        }
        // no match
        return this;
    }

    @Override
    public void serialize(DataOutput out) throws IOException {
        writeArray(out);
    }

    @Override
    public int serializedSizeInBytes() {
        return 2 + 2 * nbrruns;
    }

    @Override
    public void trim() {
        if(valueslength.length == 2 * nbrruns) return;
        valueslength = Arrays.copyOf(valueslength, 2 * nbrruns);
    }

    @Override
    protected void writeArray(DataOutput out) throws IOException {
        out.writeShort(Short.reverseBytes((short) this.nbrruns));
        for (int k = 0; k < 2 * this.nbrruns; ++k) {
            out.writeShort(Short.reverseBytes(this.valueslength[k]));
        }
    }

    @Override
    public Container xor(ArrayContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container xor(BitmapContainer x) {
        BitmapContainer answer = x.clone();
        for(int rlepos = 0; rlepos < this.nbrruns; ++rlepos ) {
            int start = Util.toIntUnsigned(this.getValue(rlepos));
            int end = Util.toIntUnsigned(this.getValue(rlepos)) + Util.toIntUnsigned(this.getLength(rlepos)) + 1;
            Util.flipBitmapRange(x.bitmap, start, end);
        }
        answer.computeCardinality();
        if(answer.getCardinality() > ArrayContainer.DEFAULT_MAX_SIZE)
            return answer;
        else return answer.toArrayContainer();
    }

    @Override
    public int rank(short lowbits) {
        int x = Util.toIntUnsigned(lowbits);
        int answer = 0;
        for (int k = 0; k < this.nbrruns; ++k) {
            int value = Util.toIntUnsigned(getValue(k));
            int length = Util.toIntUnsigned(getLength(k));
            if (x < value) {
                return answer;
            } else if (value + length + 1 >= x) {
                return answer + x - value + 1;
            }
            answer += length + 1;
        }
        return answer;
    }

    @Override
    public short select(int j) {
        int offset = 0;
        for (int k = 0; k < this.nbrruns; ++k) {
            int nextOffset = offset + Util.toIntUnsigned(getLength(k)) + 1;
            if(nextOffset > j) {
                return (short)(getValue(k) + (j - offset));
            }
            offset = nextOffset;
        }
        throw new IllegalArgumentException("Cannot select "+j+" since cardinality is "+getCardinality());        
    }

    @Override
    public Container limit(int maxcardinality) {
        if(maxcardinality >= getCardinality()) {
            return clone();
        }

        int r;
        int cardinality = 0;
        for (r = 1; r <= this.nbrruns; ++r) {
            cardinality += Util.toIntUnsigned(getLength(r)) + 1;
            if (maxcardinality <= cardinality) {
                break;
            }
        }
        RunContainer rc = new RunContainer(r, Arrays.copyOf(valueslength, 2*r));
        rc.setLength(r - 1, (short) (Util.toIntUnsigned(rc.getLength(r - 1)) - cardinality + maxcardinality));
        return rc;
    }

    @Override
    public Container iadd(int begin, int end) {
        int bIndex = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, (short) begin);
        int eIndex = unsignedInterleavedBinarySearch(this.valueslength, 0, this.nbrruns, (short) (end-1));
        if(bIndex < 0) {
            bIndex = -bIndex - 2;
        }
        if(eIndex < 0) {
            eIndex = -eIndex - 2;
        }

        int value = begin;
        int length = end - begin - 1;

        if(bIndex >= 0) {
            int bValue = Util.toIntUnsigned(getValue(bIndex));
            int bOffset = begin - bValue;
            int bLength = Util.toIntUnsigned(getLength(bIndex));
            if (bOffset <= bLength + 1) {
                bIndex--;
                value = bValue;
                length += bOffset;
            }
        }

        if(eIndex == -1) {
            int neIndex = eIndex + 1;
            if(neIndex < this.nbrruns) {
                int neValue = Util.toIntUnsigned(getValue(neIndex));
                if(neValue == end) {
                    eIndex++;
                    int neLength = Util.toIntUnsigned(getLength(neIndex));
                    length += neLength + 1;
                }
            }
        } else {
            int eValue = Util.toIntUnsigned(getValue(eIndex));
            int eOffset = (end - 1) - eValue;
            int eLength = Util.toIntUnsigned(getLength(eIndex));
            if (eOffset < eLength) {
                length += eLength - eOffset;
            }
            int neIndex = eIndex + 1;
            if(neIndex < this.nbrruns) {
                int neValue = Util.toIntUnsigned(getValue(neIndex));
                if(neValue == end) {
                    eIndex++;
                    int neLength = Util.toIntUnsigned(getLength(neIndex));
                    length += neLength + 1;
                }
            }
        }

        int nbrruns = this.nbrruns - (eIndex - (bIndex + 1));
        short[] valueslength = new short[2 * nbrruns];
        copyValuesLength(this.valueslength, 0, valueslength, 0, bIndex + 1);
        if(eIndex + 1 < this.nbrruns) {
            copyValuesLength(this.valueslength, eIndex + 1, valueslength, bIndex + 2, this.nbrruns - 1 - eIndex);
        }
        setValue(valueslength, bIndex + 1, (short) value);
        setLength(valueslength, bIndex + 1, (short) length);

        return new RunContainer(nbrruns, valueslength);
    }

    @Override
    public Container iremove(int begin, int end) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container remove(int begin, int end) {
        // TODO Auto-generated method stub
        return null;
    }


    @Override
    public boolean equals(Object o) {
        if (o instanceof RunContainer) {
            RunContainer srb = (RunContainer) o;
            if (srb.nbrruns != this.nbrruns)
                return false;
            for (int i = 0; i < nbrruns; ++i) {
                if (this.getValue(i) != srb.getValue(i))
                    return false;
                if (this.getLength(i) != srb.getLength(i))
                    return false;
            }
            return true;
        } else if(o instanceof Container) {
            if(((Container) o).getCardinality() != this.getCardinality())
                return false; // should be a frequent branch if they differ
            // next bit could be optimized if needed:
            ShortIterator me = this.getShortIterator();
            ShortIterator you = ((Container) o).getShortIterator();
            while(me.hasNext()) {
                if(me.next() != you.next())
                    return false;
            }
            return true;
        }
        return false;
    }


    protected static int unsignedInterleavedBinarySearch(final short[] array,
            final int begin, final int end, final short k) {
        int ikey = Util.toIntUnsigned(k);
        int low = begin;
        int high = end - 1;
        while (low <= high) {
            final int middleIndex = (low + high) >>> 1;
            final int middleValue = Util.toIntUnsigned(array[2 * middleIndex]);
            if (middleValue < ikey)
                low = middleIndex + 1;
            else if (middleValue > ikey)
                high = middleIndex - 1;
            else
                return middleIndex;
        }
        return -(low + 1);
    }

    short getValue(int index) {
        return valueslength[2*index];
    }

    short getLength(int index) {
        return valueslength[2*index + 1];
    }
    
    private void incrementLength(int index) {
        valueslength[2*index + 1]++;
    }
    
    private void incrementValue(int index) {
        valueslength[2*index]++;
    }

    private void setLength(int index, short v) {
        setLength(valueslength, index, v);
    }

    private void setLength(short[] valueslength, int index, short v) {
        valueslength[2*index + 1] = v;
    }

    private void setValue(int index, short v) {
        setValue(valueslength, index, v);
    }

    private void setValue(short[] valueslength, int index, short v) {
        valueslength[2*index] = v;
    }

    private void decrementLength(int index) {
        valueslength[2*index + 1]--;
    }

    private void decrementValue(int index) {
        valueslength[2*index]--;
    }
    
    private void makeRoomAtIndex(int index) {
        if (2 * nbrruns == valueslength.length) increaseCapacity();
        copyValuesLength(valueslength, index, valueslength, index + 1, nbrruns - index);
        nbrruns++;
    }

    private void recoverRoomAtIndex(int index) {
        copyValuesLength(valueslength, index + 1, valueslength, index, nbrruns - index - 1);
        nbrruns--;
    }

    private void copyValuesLength(short[] src, int srcIndex, short[] dst, int dstIndex, int length) {
        System.arraycopy(src, 2*srcIndex, dst, 2*dstIndex, 2*length);
    }

    @Override
    public Container and(RunContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container andNot(RunContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container iand(RunContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container iandNot(RunContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container ior(RunContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container ixor(RunContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container or(RunContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Container xor(RunContainer x) {
        // TODO Auto-generated method stub
        return null;
    }

}


final class RunContainerShortIterator implements ShortIterator {
    int pos;
    int le = 0;

    RunContainer parent;

    RunContainerShortIterator(RunContainer p) {
        wrap(p);
    }
    
    void wrap(RunContainer p) {
        parent = p;
        pos = 0;
        le = 0;
    }

    @Override
    public boolean hasNext() {
        return (pos < parent.nbrruns) && (le <= Util.toIntUnsigned(parent.getLength(pos)));
    }
    
    @Override
    public ShortIterator clone() {
        try {
            return (ShortIterator) super.clone();
        } catch (CloneNotSupportedException e) {
            return null;// will not happen
        }
    }

    @Override
    public short next() {
        short ans = (short) (parent.getValue(pos) + le);
        le++;
        if(le > Util.toIntUnsigned(parent.getLength(pos))) {
            pos++;
            le = 0;
        }
        return ans;
    }

    @Override
    public void remove() {
        throw new RuntimeException("Not implemented");// TODO
    }

};

final class ReverseRunContainerShortIterator implements ShortIterator {
    int pos;
    int le;
    RunContainer parent;

    ReverseRunContainerShortIterator(RunContainer p) {
        wrap(p);
    }
    
    void wrap(RunContainer p) {
        parent = p;
        pos = parent.nbrruns - 1;
        le = 0;
    }

    @Override
    public boolean hasNext() {
        return (pos >= 0) && (le <= Util.toIntUnsigned(parent.getLength(pos)));
    }
    
    @Override
    public ShortIterator clone() {
        try {
            return (ShortIterator) super.clone();
        } catch (CloneNotSupportedException e) {
            return null;// will not happen
        }
    }

    @Override
    public short next() {
        short ans = (short) (parent.getValue(pos) + Util.toIntUnsigned(parent.getLength(pos)) - le);
        le++;
        if(le > Util.toIntUnsigned(parent.getLength(pos))) {
            pos--;
            le = 0;
        }
        return ans;
    }

    @Override
    public void remove() {
        throw new RuntimeException("Not implemented");// TODO
    }

}

