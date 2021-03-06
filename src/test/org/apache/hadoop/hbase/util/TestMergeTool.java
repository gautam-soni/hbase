/**
 * Copyright 2008 The Apache Software Foundation
 *
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

package org.apache.hadoop.hbase.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.util.ToolRunner;

import org.apache.hadoop.dfs.MiniDFSCluster;
import org.apache.hadoop.hbase.HBaseTestCase;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HLog;
import org.apache.hadoop.hbase.HRegion;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.StaticTestEnvironment;
import org.apache.hadoop.hbase.io.BatchUpdate;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;

/** Test stand alone merge tool that can merge arbitrary regions */
public class TestMergeTool extends HBaseTestCase {
  static final Log LOG = LogFactory.getLog(TestMergeTool.class);
  protected static final Text COLUMN_NAME = new Text("contents:");
  private final HRegionInfo[] sourceRegions = new HRegionInfo[5];
  private final HRegion[] regions = new HRegion[5];
  private HTableDescriptor desc;
  private Text[][] rows;
  private Path rootdir = null;
  private MiniDFSCluster dfsCluster = null;
  private FileSystem fs;
  
  /** {@inheritDoc} */
  @Override
  public void setUp() throws Exception {

    // Create table description
    
    this.desc = new HTableDescriptor("TestMergeTool");
    this.desc.addFamily(new HColumnDescriptor(COLUMN_NAME.toString()));

    /*
     * Create the HRegionInfos for the regions.
     */
    
    // Region 0 will contain the key range [row_0200,row_0300)
    sourceRegions[0] =
      new HRegionInfo(this.desc, new Text("row_0200"), new Text("row_0300"));
    
    // Region 1 will contain the key range [row_0250,row_0400) and overlaps
    // with Region 0
    sourceRegions[1] =
      new HRegionInfo(this.desc, new Text("row_0250"), new Text("row_0400"));
    
    // Region 2 will contain the key range [row_0100,row_0200) and is adjacent
    // to Region 0 or the region resulting from the merge of Regions 0 and 1
    sourceRegions[2] =
      new HRegionInfo(this.desc, new Text("row_0100"), new Text("row_0200"));
    
    // Region 3 will contain the key range [row_0500,row_0600) and is not
    // adjacent to any of Regions 0, 1, 2 or the merged result of any or all
    // of those regions
    sourceRegions[3] =
      new HRegionInfo(this.desc, new Text("row_0500"), new Text("row_0600"));
    
    // Region 4 will have empty start and end keys and overlaps all regions.
    sourceRegions[4] =
      new HRegionInfo(this.desc, HConstants.EMPTY_TEXT, HConstants.EMPTY_TEXT);
    
    /*
     * Now create some row keys
     */
    this.rows = new Text[5][];
    this.rows[0] = new Text[] { new Text("row_0210"), new Text("row_0280") };
    this.rows[1] = new Text[] { new Text("row_0260"), new Text("row_0350") };
    this.rows[2] = new Text[] { new Text("row_0110"), new Text("row_0175") };
    this.rows[3] = new Text[] { new Text("row_0525"), new Text("row_0560") };
    this.rows[4] = new Text[] { new Text("row_0050"), new Text("row_1000") };
    
    // Start up dfs
    this.dfsCluster = new MiniDFSCluster(conf, 2, true, (String[])null);
    this.fs = this.dfsCluster.getFileSystem();
    // Set the hbase.rootdir to be the home directory in mini dfs.
    this.rootdir = new Path(this.fs.getHomeDirectory(), "hbase");
    this.conf.set(HConstants.HBASE_DIR, this.rootdir.toString());
    
    // Note: we must call super.setUp after starting the mini cluster or
    // we will end up with a local file system
    
    super.setUp();

    try {
      /*
       * Create the regions we will merge
       */
      for (int i = 0; i < sourceRegions.length; i++) {
        regions[i] =
          HRegion.createHRegion(this.sourceRegions[i], this.rootdir, this.conf);
        /*
         * Insert data
         */
        for (int j = 0; j < rows[i].length; j++) {
          BatchUpdate b = new BatchUpdate();
          Text row = rows[i][j];
          long id = b.startUpdate(row);
          b.put(id, COLUMN_NAME,
              new ImmutableBytesWritable(
                  row.getBytes(), 0, row.getLength()
              ).get()
          );
          regions[i].batchUpdate(HConstants.LATEST_TIMESTAMP, b);
        }
      }
      // Create root region
      HRegion root = HRegion.createHRegion(HRegionInfo.rootRegionInfo,
          this.rootdir, this.conf);
      // Create meta region
      HRegion meta = HRegion.createHRegion(HRegionInfo.firstMetaRegionInfo,
          this.rootdir, this.conf);
      // Insert meta into root region
      HRegion.addRegionToMETA(root, meta);
      // Insert the regions we created into the meta
      for(int i = 0; i < regions.length; i++) {
        HRegion.addRegionToMETA(meta, regions[i]);
      }
      // Close root and meta regions
      root.close();
      root.getLog().closeAndDelete();
      meta.close();
      meta.getLog().closeAndDelete();
      
    } catch (Exception e) {
      StaticTestEnvironment.shutdownDfs(dfsCluster);
      throw e;
    }
  }

  /** {@inheritDoc} */
  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    StaticTestEnvironment.shutdownDfs(dfsCluster);
  }

  /** @throws Exception */
  public void testMergeTool() throws Exception {
    // First verify we can read the rows from the source regions and that they
    // contain the right data.
    for (int i = 0; i < regions.length; i++) {
      for (int j = 0; j < rows[i].length; j++) {
        byte[] bytes = regions[i].get(rows[i][j], COLUMN_NAME);
        assertNotNull(bytes);
        Text value = new Text(bytes);
        assertTrue(value.equals(rows[i][j]));
      }
      // Close the region and delete the log
      regions[i].close();
      regions[i].getLog().closeAndDelete();
    }

    // Create a log that we can reuse when we need to open regions
    
    HLog log = new HLog(this.fs, 
        new Path("/tmp", HConstants.HREGION_LOGDIR_NAME + "_" +
            System.currentTimeMillis()
        ),
        this.conf, null
    );
    try {
      /*
       * Merge Region 0 and Region 1
       */
      LOG.info("merging regions 0 and 1");
      Merge merger = new Merge(this.conf);
      ToolRunner.run(merger,
          new String[] {
          this.desc.getName().toString(),
          this.sourceRegions[0].getRegionName().toString(),
          this.sourceRegions[1].getRegionName().toString()
      }
      );
      HRegionInfo mergedInfo = merger.getMergedHRegionInfo();
    
      // Now verify that we can read all the rows from regions 0, 1
      // in the new merged region.
      HRegion merged =
        HRegion.openHRegion(mergedInfo, this.rootdir, log, this.conf);
      
      for (int i = 0; i < 2 ; i++) {
        for (int j = 0; j < rows[i].length; j++) {
          byte[] bytes = merged.get(rows[i][j], COLUMN_NAME);
          assertNotNull(rows[i][j].toString(), bytes);
          Text value = new Text(bytes);
          assertTrue(value.equals(rows[i][j]));
        }
      }
      merged.close();
      LOG.info("verified merge of regions 0 and 1");
      /*
       * Merge the result of merging regions 0 and 1 with region 2
       */
      LOG.info("merging regions 0+1 and 2");
      merger = new Merge(this.conf);
      ToolRunner.run(merger,
          new String[] {
            this.desc.getName().toString(),
            mergedInfo.getRegionName().toString(),
            this.sourceRegions[2].getRegionName().toString()
          }
      );
      mergedInfo = merger.getMergedHRegionInfo();

      // Now verify that we can read all the rows from regions 0, 1 and 2
      // in the new merged region.
      
      merged = HRegion.openHRegion(mergedInfo, this.rootdir, log, this.conf);

      for (int i = 0; i < 3 ; i++) {
        for (int j = 0; j < rows[i].length; j++) {
          byte[] bytes = merged.get(rows[i][j], COLUMN_NAME);
          assertNotNull(bytes);
          Text value = new Text(bytes);
          assertTrue(value.equals(rows[i][j]));
        }
      }
      merged.close();
      LOG.info("verified merge of regions 0+1 and 2");
      /*
       * Merge the result of merging regions 0, 1 and 2 with region 3
       */
      LOG.info("merging regions 0+1+2 and 3");
      merger = new Merge(this.conf);
      ToolRunner.run(merger,
          new String[] {
            this.desc.getName().toString(),
            mergedInfo.getRegionName().toString(),
            this.sourceRegions[3].getRegionName().toString()
          }
      );
      mergedInfo = merger.getMergedHRegionInfo();
      
      // Now verify that we can read all the rows from regions 0, 1, 2 and 3
      // in the new merged region.
      
      merged = HRegion.openHRegion(mergedInfo, this.rootdir, log, this.conf);
      
      for (int i = 0; i < 4 ; i++) {
        for (int j = 0; j < rows[i].length; j++) {
          byte[] bytes = merged.get(rows[i][j], COLUMN_NAME);
          assertNotNull(bytes);
          Text value = new Text(bytes);
          assertTrue(value.equals(rows[i][j]));
        }
      }
      merged.close();
      LOG.info("verified merge of regions 0+1+2 and 3");
      /*
       * Merge the result of merging regions 0, 1, 2 and 3 with region 4
       */
      LOG.info("merging regions 0+1+2+3 and 4");
      merger = new Merge(this.conf);
      ToolRunner.run(merger,
          new String[] {
            this.desc.getName().toString(),
            mergedInfo.getRegionName().toString(),
            this.sourceRegions[4].getRegionName().toString()
          }
      );
      mergedInfo = merger.getMergedHRegionInfo();
      
      // Now verify that we can read all the rows from the new merged region.

      merged = HRegion.openHRegion(mergedInfo, this.rootdir, log, this.conf);
      
      for (int i = 0; i < rows.length ; i++) {
        for (int j = 0; j < rows[i].length; j++) {
          byte[] bytes = merged.get(rows[i][j], COLUMN_NAME);
          assertNotNull(bytes);
          Text value = new Text(bytes);
          assertTrue(value.equals(rows[i][j]));
        }
      }
      merged.close();
      LOG.info("verified merge of regions 0+1+2+3 and 4");
      
    } finally {
      log.closeAndDelete();
    }
  }
}
