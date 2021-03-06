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
package org.apache.hadoop.hbase;

import java.io.IOException;

import org.apache.hadoop.io.Text;

/**
 * Test setting the global memcache size for a region server. When it reaches 
 * this size, any puts should be blocked while one or more forced flushes occurs
 * to bring the memcache size back down. 
 */
public class TestGlobalMemcacheLimit extends HBaseClusterTestCase {
  final byte[] ONE_KB = new byte[1024];

  HTable table1;
  HTable table2;
  HRegionServer server;
  
  long keySize = (new Text(COLFAMILY_NAME1)).getLength() + 9 + 8;
  long rowSize = keySize + ONE_KB.length;

  /** {@inheritDoc} */
  @Override
  public void setUp() throws Exception {
    preHBaseClusterSetup();
    super.setUp();
    postHBaseClusterSetup();
  }
  
  /**
   * Get our hands into the cluster configuration before the hbase cluster 
   * starts up.
   */
  private void preHBaseClusterSetup() {
    // we'll use a 2MB global memcache for testing's sake.
    conf.setInt("hbase.regionserver.globalMemcacheLimit", 2 * 1024 * 1024);
    // low memcache mark will be 1MB
    conf.setInt("hbase.regionserver.globalMemcacheLimitLowMark", 
      1 * 1024 * 1024);
    // make sure we don't do any optional flushes and confuse my tests.
    conf.setInt("hbase.regionserver.optionalcacheflushinterval", 120000);
  }
  
  /**
   * Create a table that we'll use to test.
   */
  private void postHBaseClusterSetup() throws IOException {
    HTableDescriptor desc1 = createTableDescriptor("testTable1");
    HTableDescriptor desc2 = createTableDescriptor("testTable2");
    HBaseAdmin admin = new HBaseAdmin(conf);
    admin.createTable(desc1);
    admin.createTable(desc2);
    table1 = new HTable(conf, new Text("testTable1"));
    table2 = new HTable(conf, new Text("testTable2"));    
    server = cluster.getRegionServer(0);    
    
    // there is a META region in play, and those are probably still in
    // the memcache for ROOT. flush it out.
    for (HRegion region : server.getOnlineRegions().values()) {
      region.flushcache();
    }
    // make sure we're starting at 0 so that it's easy to predict what the 
    // results of our tests should be.
    assertEquals("Starting memcache size", 0, server.getGlobalMemcacheSize());
  }
  
  /**
   * Make sure that region server thinks all the memcaches are as big as we were
   * hoping they would be.
   * @throws IOException
   */
  public void testMemcacheSizeAccounting() throws IOException {
    // put some data in each of the two tables
    long dataSize = populate(table1, 500, 0) + populate(table2, 500, 0);
    
    // make sure the region server says it is using as much memory as we think
    // it is.
    assertEquals("Global memcache size", dataSize, 
      server.getGlobalMemcacheSize());
  }
  
  /**
   * Test that a put gets blocked and a flush is forced as expected when we 
   * reach the memcache size limit.
   * @throws IOException
   */
  public void testBlocksAndForcesFlush() throws IOException {
    // put some data in each of the two tables
    long startingDataSize = populate(table1, 500, 0) + populate(table2, 500, 0);
    
    // at this point we have 1052000 bytes in memcache. now, we'll keep adding 
    // data to one of the tables until just before the global memcache limit,
    // noting that the globalMemcacheSize keeps growing as expected. then, we'll
    // do another put, causing it to go over the limit. when we look at the
    // globablMemcacheSize now, it should be <= the low limit. 
    long dataNeeded = (2 * 1024 * 1024) - startingDataSize;
    double numRows = (double)dataNeeded / (double)rowSize;
    int preFlushRows = (int)Math.floor(numRows);
  
    long dataAdded = populate(table1, preFlushRows, 500);
    assertEquals("Expected memcache size", dataAdded + startingDataSize, 
      server.getGlobalMemcacheSize());
        
    populate(table1, 2, preFlushRows + 500);
    assertTrue("Post-flush memcache size", server.getGlobalMemcacheSize() <= 1024 * 1024);
  }
  
  private long populate(HTable table, int numRows, int startKey) throws IOException {
    long total = 0;
    Text column = new Text(COLFAMILY_NAME1);
    for (int i = startKey; i < startKey + numRows; i++) {
      Text key = new Text("row_" + String.format("%1$5d", i));
      total += key.getLength();
      total += column.getLength();
      total += 8;
      total += ONE_KB.length;
      long id = table.startUpdate(key);
      table.put(id, column, ONE_KB);
      table.commit(id);
    }
    return total;
  }
}
