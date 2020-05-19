/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.index;

import org.apache.commons.cli.CommandLine;
import org.apache.phoenix.end2end.IndexToolIT;
import org.apache.phoenix.mapreduce.index.IndexTool;
import org.apache.phoenix.query.BaseTest;
import org.apache.phoenix.schema.PTable;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import static org.apache.phoenix.mapreduce.index.IndexTool.FEATURE_NOT_APPLICABLE;
import static org.apache.phoenix.mapreduce.index.IndexTool.INVALID_TIME_RANGE_EXCEPTION_MESSAGE;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

public class IndexToolTest extends BaseTest {

    IndexTool it;
    private String dataTable;
    private String indexTable;
    private String schema;
    private String tenantId;
    @Mock
    PTable pDataTable;
    boolean localIndex = true;

    @Rule
    public ExpectedException exceptionRule = ExpectedException.none();

    @Before
    public void setup() {
        it = new IndexTool();
        schema = generateUniqueName();
        dataTable = generateUniqueName();
        indexTable = generateUniqueName();
        tenantId = generateUniqueName();
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testParseOptions_timeRange_timeRangeNotNull() {
        Long startTime = 10L;
        Long endTime = 15L;
        String [] args =
                IndexToolIT.getArgValues(true, true, schema,
                        dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE,
                        startTime , endTime);
        CommandLine cmdLine = it.parseOptions(args);
        it.populateIndexToolAttributes(cmdLine);
        assertEquals(startTime, it.getStartTime());
        assertEquals(endTime, it.getEndTime());
    }

    @Test
    public void testParseOptions_timeRange_null() {
        String [] args =
                IndexToolIT.getArgValues(true, true, schema,
                        dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE);
        CommandLine cmdLine = it.parseOptions(args);
        it.populateIndexToolAttributes(cmdLine);
        Assert.assertNull(it.getStartTime());
        Assert.assertNull(it.getEndTime());
    }

    @Test
    public void testParseOptions_timeRange_startTimeNotNull() {
        Long startTime = 10L;
        String [] args =
                IndexToolIT.getArgValues(true, true, schema,
                        dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE,
                        startTime , null);
        CommandLine cmdLine = it.parseOptions(args);
        it.populateIndexToolAttributes(cmdLine);
        assertEquals(startTime, it.getStartTime());
        assertEquals(null, it.getEndTime());
    }

    @Test
    public void testParseOptions_timeRange_endTimeNotNull() {
        Long endTime = 15L;
        String [] args =
                IndexToolIT.getArgValues(true, true, schema,
                        dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE,
                        null , endTime);
        CommandLine cmdLine = it.parseOptions(args);
        it.populateIndexToolAttributes(cmdLine);
        assertEquals(null, it.getStartTime());
        assertEquals(endTime, it.getEndTime());
    }

    @Test
    public void testParseOptions_timeRange_startTimeNullEndTimeInFuture() {
        Long endTime = EnvironmentEdgeManager.currentTimeMillis() + 100000;
        String [] args =
                IndexToolIT.getArgValues(true, true, schema,
                        dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE,
                        null , endTime);
        CommandLine cmdLine = it.parseOptions(args);
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage(INVALID_TIME_RANGE_EXCEPTION_MESSAGE);
        it.populateIndexToolAttributes(cmdLine);
    }

    @Test
    public void testParseOptions_timeRange_endTimeNullStartTimeInFuture() {
        Long startTime = EnvironmentEdgeManager.currentTimeMillis() + 100000;
        String [] args =
                IndexToolIT.getArgValues(true, true, schema,
                        dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE,
                        startTime , null);
        CommandLine cmdLine = it.parseOptions(args);
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage(INVALID_TIME_RANGE_EXCEPTION_MESSAGE);
        it.populateIndexToolAttributes(cmdLine);
    }

    @Test(timeout = 10000 /* 10 secs */)
    public void testParseOptions_timeRange_startTimeInFuture() {
        Long startTime = EnvironmentEdgeManager.currentTimeMillis() + 100000;
        Long endTime = EnvironmentEdgeManager.currentTimeMillis() + 200000;
        String [] args =
                IndexToolIT.getArgValues(true, true, schema,
                        dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE,
                        startTime , endTime);
        CommandLine cmdLine = it.parseOptions(args);
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage(INVALID_TIME_RANGE_EXCEPTION_MESSAGE);
        it.populateIndexToolAttributes(cmdLine);
    }

    @Test(timeout = 10000 /* 10 secs */)
    public void testParseOptions_timeRange_endTimeInFuture() {
        Long startTime = EnvironmentEdgeManager.currentTimeMillis();
        Long endTime = EnvironmentEdgeManager.currentTimeMillis() + 100000;
        String [] args =
                IndexToolIT.getArgValues(true, true, schema,
                        dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE,
                        startTime , endTime);
        CommandLine cmdLine = it.parseOptions(args);
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage(INVALID_TIME_RANGE_EXCEPTION_MESSAGE);
        it.populateIndexToolAttributes(cmdLine);
    }

    @Test
    public void testParseOptions_timeRange_startTimeEqEndTime() {
        Long startTime = 10L;
        Long endTime = 10L;
        String [] args =
                IndexToolIT.getArgValues(true, true, schema,
                        dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE,
                        startTime , endTime);
        CommandLine cmdLine = it.parseOptions(args);
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage(INVALID_TIME_RANGE_EXCEPTION_MESSAGE);
        it.populateIndexToolAttributes(cmdLine);
    }

    @Test
    public void testParseOptions_timeRange_startTimeGtEndTime() {
        Long startTime = 10L;
        Long endTime = 1L;
        String [] args =
                IndexToolIT.getArgValues(true, true, schema,
                        dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE,
                        startTime , endTime);
        CommandLine cmdLine = it.parseOptions(args);
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage(INVALID_TIME_RANGE_EXCEPTION_MESSAGE);
        it.populateIndexToolAttributes(cmdLine);
    }

    @Test
    public void testCheckTimeRangeFeature_timeRangeSet_transactionalTable_globalIndex() {
        when(pDataTable.isTransactional()).thenReturn(true);
        exceptionRule.expect(RuntimeException.class);
        exceptionRule.expectMessage(FEATURE_NOT_APPLICABLE);
        IndexTool.checkTimeRangeFeature(1L, 3L, pDataTable, !localIndex);
    }

    @Test
    public void testCheckVerifyAndDisableLogging_defaultsNone(){
        Long startTime = 1L;
        Long endTime = 10L;
        String [] args =
            IndexToolIT.getArgValues(true, true, schema,
                dataTable, indexTable, tenantId, IndexTool.IndexVerifyType.NONE,
                startTime , endTime);
        CommandLine cmdLine = it.parseOptions(args);
        it.populateIndexToolAttributes(cmdLine);
        assertEquals(IndexTool.IndexDisableLoggingType.NONE, it.getDisableLoggingType());
    }

    @Test
    public void testDisableLogging_allowsNone(){
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.NONE, IndexTool.IndexVerifyType.NONE);
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.NONE, IndexTool.IndexVerifyType.ONLY);
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.NONE, IndexTool.IndexVerifyType.BEFORE);
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.NONE, IndexTool.IndexVerifyType.AFTER);
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.NONE, IndexTool.IndexVerifyType.BOTH);
    }

    @Test
    public void testDisableLogging_allowsBefore(){
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.BEFORE, IndexTool.IndexVerifyType.BEFORE);
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.BEFORE, IndexTool.IndexVerifyType.ONLY);
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.BEFORE, IndexTool.IndexVerifyType.BOTH);
        verifyDisableLoggingException(IndexTool.IndexDisableLoggingType.BEFORE,
            IndexTool.IndexVerifyType.AFTER);
        verifyDisableLoggingException(IndexTool.IndexDisableLoggingType.BEFORE,
            IndexTool.IndexVerifyType.NONE);
    }

    @Test
    public void testDisableLogging_allowsAfter(){
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.AFTER, IndexTool.IndexVerifyType.BOTH);
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.AFTER, IndexTool.IndexVerifyType.AFTER);
        verifyDisableLoggingException(IndexTool.IndexDisableLoggingType.AFTER,
            IndexTool.IndexVerifyType.NONE);
        verifyDisableLoggingException(IndexTool.IndexDisableLoggingType.AFTER,
            IndexTool.IndexVerifyType.BEFORE);
        verifyDisableLoggingException(IndexTool.IndexDisableLoggingType.BOTH,
            IndexTool.IndexVerifyType.ONLY);
    }

    @Test
    public void testCheckVerifyAndDisableLogging_allowsBoth(){
        verifyDisableLogging(IndexTool.IndexDisableLoggingType.BOTH, IndexTool.IndexVerifyType.BOTH);
        verifyDisableLoggingException(IndexTool.IndexDisableLoggingType.BOTH,
            IndexTool.IndexVerifyType.NONE);
        verifyDisableLoggingException(IndexTool.IndexDisableLoggingType.BOTH,
            IndexTool.IndexVerifyType.ONLY);
        verifyDisableLoggingException(IndexTool.IndexDisableLoggingType.BOTH,
            IndexTool.IndexVerifyType.BEFORE);
        verifyDisableLoggingException(IndexTool.IndexDisableLoggingType.BOTH,
            IndexTool.IndexVerifyType.AFTER);
    }

    public void verifyDisableLogging(IndexTool.IndexDisableLoggingType disableType,
                                     IndexTool.IndexVerifyType verifyType) {
        Long startTime = 1L;
        Long endTime = 10L;
        String[] args =
            IndexToolIT.getArgValues(true, true, schema,
                dataTable, indexTable, tenantId, verifyType,
                startTime, endTime, disableType);
        CommandLine cmdLine = it.parseOptions(args);
        it.populateIndexToolAttributes(cmdLine);
        assertEquals(disableType, it.getDisableLoggingType());
    }

    public void verifyDisableLoggingException(IndexTool.IndexDisableLoggingType disableType,
                                     IndexTool.IndexVerifyType verifyType) {
        Long startTime = 1L;
        Long endTime = 10L;
        String[] args =
            IndexToolIT.getArgValues(true, true, schema,
                dataTable, indexTable, tenantId, verifyType,
                startTime, endTime, disableType);
        exceptionRule.expect(IllegalStateException.class);
        CommandLine cmdLine = it.parseOptions(args);
    }
}
