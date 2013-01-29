/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.namenode;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.EnumSet;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream;
import org.apache.hadoop.hdfs.client.HdfsDataOutputStream.SyncFlag;
import org.apache.hadoop.hdfs.server.namenode.NNStorage.NameNodeFile;
import org.apache.hadoop.hdfs.server.namenode.snapshot.SnapshotTestHelper;
import org.apache.hadoop.hdfs.util.Canceler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test FSImage save/load when Snapshot is supported
 */
public class TestFSImageWithSnapshot {
  static final long seed = 0;
  static final short REPLICATION = 3;
  static final int BLOCKSIZE = 1024;
  static final long txid = 1;

  private final Path rootDir = new Path("/");
  private final Path dir = new Path("/TestSnapshot");
  private static String testDir =
      System.getProperty("test.build.data", "build/test/data");
  
  Configuration conf;
  MiniDFSCluster cluster;
  FSNamesystem fsn;
  DistributedFileSystem hdfs;
  
  @Before
  public void setUp() throws Exception {
    conf = new Configuration();
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(REPLICATION)
        .build();
    cluster.waitActive();
    fsn = cluster.getNamesystem();
    hdfs = cluster.getFileSystem();
  }

  @After
  public void tearDown() throws Exception {
    if (cluster != null) {
      cluster.shutdown();
    }
  }
  
  /**
   * Create a temp fsimage file for testing.
   * @param dir The directory where the fsimage file resides
   * @param imageTxId The transaction id of the fsimage
   * @return The file of the image file
   */
  private File getImageFile(String dir, long imageTxId) {
    return new File(dir, String.format("%s_%019d", NameNodeFile.IMAGE,
        imageTxId));
  }
  
  /** 
   * Create a temp file for dumping the fsdir
   * @param dir directory for the temp file
   * @param suffix suffix of of the temp file
   * @return the temp file
   */
  private File getDumpTreeFile(String dir, String suffix) {
    return new File(dir, String.format("dumpTree_%s", suffix));
  }
  
  /** 
   * Dump the fsdir tree to a temp file
   * @param fileSuffix suffix of the temp file for dumping
   * @return the temp file
   */
  private File dumpTree2File(String fileSuffix) throws IOException {
    File file = getDumpTreeFile(testDir, fileSuffix);
    PrintWriter out = new PrintWriter(new FileWriter(file, false), true);
    fsn.getFSDirectory().getINode(rootDir.toString())
        .dumpTreeRecursively(out, new StringBuilder(), null);
    out.close();
    return file;
  }
  
  /** Append a file without closing the output stream */
  private HdfsDataOutputStream appendFileWithoutClosing(Path file, int length)
      throws IOException {
    byte[] toAppend = new byte[length];
    Random random = new Random();
    random.nextBytes(toAppend);
    HdfsDataOutputStream out = (HdfsDataOutputStream) hdfs.append(file);
    out.write(toAppend);
    return out;
  }
  
  /** Save the fsimage to a temp file */
  private File saveFSImageToTempFile() throws IOException {
    SaveNamespaceContext context = new SaveNamespaceContext(fsn, txid,
        new Canceler());
    FSImageFormat.Saver saver = new FSImageFormat.Saver(context);
    FSImageCompression compression = FSImageCompression.createCompression(conf);
    File imageFile = getImageFile(testDir, txid);
    fsn.readLock();
    try {
      saver.save(imageFile, compression);
    } finally {
      fsn.readUnlock();
    }
    return imageFile;
  }
  
  /** Load the fsimage from a temp file */
  private void loadFSImageFromTempFile(File imageFile) throws IOException {
    FSImageFormat.Loader loader = new FSImageFormat.Loader(conf, fsn);
    fsn.writeLock();
    fsn.getFSDirectory().writeLock();
    try {
      loader.load(imageFile);
    } finally {
      fsn.getFSDirectory().writeUnlock();
      fsn.writeUnlock();
    }
  }
  
  /**
   * Testing steps:
   * <pre>
   * 1. Creating/modifying directories/files while snapshots are being taken.
   * 2. Dump the FSDirectory tree of the namesystem.
   * 3. Save the namesystem to a temp file (FSImage saving).
   * 4. Restart the cluster and format the namesystem.
   * 5. Load the namesystem from the temp file (FSImage loading).
   * 6. Dump the FSDirectory again and compare the two dumped string.
   * </pre>
   */
  @Test
  public void testSaveLoadImage() throws Exception {
    // make changes to the namesystem
    hdfs.mkdirs(dir);
    hdfs.allowSnapshot(dir.toString());
    hdfs.createSnapshot(dir, "s0");
    
    Path sub1 = new Path(dir, "sub1");
    Path sub1file1 = new Path(sub1, "sub1file1");
    Path sub1file2 = new Path(sub1, "sub1file2");
    DFSTestUtil.createFile(hdfs, sub1file1, BLOCKSIZE, REPLICATION, seed);
    DFSTestUtil.createFile(hdfs, sub1file2, BLOCKSIZE, REPLICATION, seed);
    
    hdfs.createSnapshot(dir, "s1");
    
    Path sub2 = new Path(dir, "sub2");
    Path sub2file1 = new Path(sub2, "sub2file1");
    Path sub2file2 = new Path(sub2, "sub2file2");
    DFSTestUtil.createFile(hdfs, sub2file1, BLOCKSIZE, REPLICATION, seed);
    DFSTestUtil.createFile(hdfs, sub2file2, BLOCKSIZE, REPLICATION, seed);
    hdfs.setReplication(sub1file1, (short) (REPLICATION - 1));
    hdfs.delete(sub1file2, true);
    
    hdfs.createSnapshot(dir, "s2");
    hdfs.setOwner(sub2, "dr.who", "unknown");
    hdfs.delete(sub2file2, true);
    
    // dump the fsdir tree
    File fsnBefore = dumpTree2File("before");
    
    // save the namesystem to a temp file
    File imageFile = saveFSImageToTempFile();

    // restart the cluster, and format the cluster
    cluster.shutdown();
    cluster = new MiniDFSCluster.Builder(conf).format(true)
        .numDataNodes(REPLICATION).build();
    cluster.waitActive();
    fsn = cluster.getNamesystem();
    hdfs = cluster.getFileSystem();
    
    // load the namesystem from the temp file
    loadFSImageFromTempFile(imageFile);
    
    // dump the fsdir tree again
    File fsnAfter = dumpTree2File("after");
    
    // compare two dumped tree
    SnapshotTestHelper.compareDumpedTreeInFile(fsnBefore, fsnAfter);
  }
  
  /**
   * Test the fsimage saving/loading while file appending.
   */
  @Test
  public void testSaveLoadImageWithAppending() throws Exception {
    Path sub1 = new Path(dir, "sub1");
    Path sub1file1 = new Path(sub1, "sub1file1");
    Path sub1file2 = new Path(sub1, "sub1file2");
    DFSTestUtil.createFile(hdfs, sub1file1, BLOCKSIZE, REPLICATION, seed);
    DFSTestUtil.createFile(hdfs, sub1file2, BLOCKSIZE, REPLICATION, seed);
    
    // 1. create snapshot s0
    hdfs.allowSnapshot(dir.toString());
    hdfs.createSnapshot(dir, "s0");
    
    // 2. create snapshot s1 before appending sub1file1 finishes
    HdfsDataOutputStream out = appendFileWithoutClosing(sub1file1, BLOCKSIZE);
    out.hsync(EnumSet.of(SyncFlag.UPDATE_LENGTH));
    // also append sub1file2
    DFSTestUtil.appendFile(hdfs, sub1file2, BLOCKSIZE);
    hdfs.createSnapshot(dir, "s1");
    out.close();
    
    // 3. create snapshot s2 before appending finishes
    out = appendFileWithoutClosing(sub1file1, BLOCKSIZE);
    out.hsync(EnumSet.of(SyncFlag.UPDATE_LENGTH));
    hdfs.createSnapshot(dir, "s2");
    out.close();
    
    // 4. save fsimage before appending finishes
    out = appendFileWithoutClosing(sub1file1, BLOCKSIZE);
    out.hsync(EnumSet.of(SyncFlag.UPDATE_LENGTH));
    // dump fsdir
    File fsnBefore = dumpTree2File("before");
    // save the namesystem to a temp file
    File imageFile = saveFSImageToTempFile();
    
    // 5. load fsimage and compare
    // first restart the cluster, and format the cluster
    out.close();
    cluster.shutdown();
    cluster = new MiniDFSCluster.Builder(conf).format(true)
        .numDataNodes(REPLICATION).build();
    cluster.waitActive();
    fsn = cluster.getNamesystem();
    hdfs = cluster.getFileSystem();
    // then load the fsimage
    loadFSImageFromTempFile(imageFile);
    
    // dump the fsdir tree again
    File fsnAfter = dumpTree2File("after");
    
    // compare two dumped tree
    SnapshotTestHelper.compareDumpedTreeInFile(fsnBefore, fsnAfter);
  }
}