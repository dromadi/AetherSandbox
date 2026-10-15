package com.aether.sandbox.domain;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class Iso9660Test {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static void uint(byte[] data, int at, int value) { for (int i=0; i<4; i++) data[at+i]=(byte)(value >>> (8*i)); }
    private static int entry(byte[] data, int at, String name, int sector, int length) {
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        int count = 33 + bytes.length + (bytes.length % 2 == 0 ? 1 : 0);
        data[at] = (byte)count; uint(data,at+2,sector); uint(data,at+10,length);
        data[at+32]=(byte)bytes.length; System.arraycopy(bytes,0,data,at+33,bytes.length); return at+count;
    }
    private File image(boolean outOfBounds, boolean duplicate) throws IOException {
        byte[] data = new byte[22*2048]; int p=16*2048;
        data[p]=1; System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII),0,data,p+1,5); data[p+6]=1;
        uint(data,p+158,18); uint(data,p+166,2048);
        int next=entry(data,18*2048,"kernel;1", outOfBounds ? 999999 : 20,3);
        next=entry(data,next,"initrd.img;1",21,4);
        if (duplicate) entry(data,next,"KERNEL;2",20,3);
        System.arraycopy(new byte[]{1,2,3},0,data,20*2048,3);
        System.arraycopy(new byte[]{4,5,6,7},0,data,21*2048,4);
        File file=temp.newFile(); Files.write(file.toPath(),data); return file;
    }
    @Test public void extractsOnlyTwoBootFiles() throws Exception {
        File output=temp.newFolder(); Iso9660.extractBootFiles(image(false,false),output);
        assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(output,"kernel").toPath()));
        assertArrayEquals(new byte[]{4,5,6,7},Files.readAllBytes(new File(output,"initrd.img").toPath()));
        assertEquals(2,output.list().length);
    }
    @Test public void rejectsOutsideExtentBeforeWriting() throws Exception {
        File output=temp.newFolder();
        assertThrows(IOException.class, () -> Iso9660.extractBootFiles(image(true,false),output));
        assertEquals(0,output.list().length);
    }
    @Test public void rejectsDuplicateNamesBeforeWriting() throws Exception {
        File output=temp.newFolder();
        assertThrows(IOException.class, () -> Iso9660.extractBootFiles(image(false,true),output));
        assertEquals(0,output.list().length);
    }
}
