/*
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

package org.apache.flink.runtime.jobmanager;

import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.io.network.api.writer.RecordWriter;
import org.apache.flink.runtime.io.network.partition.ResultPartitionType;
import org.apache.flink.runtime.jobgraph.DistributionPattern;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.JobVertex;
import org.apache.flink.runtime.jobgraph.tasks.AbstractInvokable;
import org.apache.flink.runtime.jobmanager.scheduler.SlotSharingGroup;
import org.apache.flink.runtime.testingUtils.TestingCluster;
import org.apache.flink.runtime.testingUtils.TestingUtils;
import org.apache.flink.types.IntValue;
import org.apache.flink.util.TestLogger;

import org.apache.flink.shaded.guava18.com.google.common.collect.Lists;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.apache.flink.runtime.jobmanager.SlotCountExceedingParallelismTest.SubtaskIndexReceiver.CONFIG_KEY;

public class LegacyScheduleOrUpdateConsumersTest extends TestLogger {

	private static final int NUMBER_OF_TMS = 2;
	private static final int NUMBER_OF_SLOTS_PER_TM = 2;
	private static final int PARALLELISM = NUMBER_OF_TMS * NUMBER_OF_SLOTS_PER_TM;

	private static TestingCluster flink;

	@BeforeClass
	public static void setUp() throws Exception {
		flink = TestingUtils.startTestingCluster(
				NUMBER_OF_SLOTS_PER_TM,
				NUMBER_OF_TMS,
				TestingUtils.DEFAULT_AKKA_ASK_TIMEOUT());
	}

	@AfterClass
	public static void tearDown() throws Exception {
		flink.stop();
	}

	/**
	 * Tests notifications of multiple receivers when a task produces both a pipelined and blocking
	 * result.
	 *
	 * <pre>
	 *                             +----------+
	 *            +-- pipelined -> | Receiver |
	 * +--------+ |                +----------+
	 * | Sender |-|
	 * +--------+ |                +----------+
	 *            +-- blocking --> | Receiver |
	 *                             +----------+
	 * </pre>
	 *
	 * The pipelined receiver gets deployed after the first buffer is available and the blocking
	 * one after all subtasks are finished.
	 */
	@Test
	public void testMixedPipelinedAndBlockingResults() throws Exception {
		final JobVertex sender = new JobVertex("Sender");
		sender.setInvokableClass(BinaryRoundRobinSubtaskIndexSender.class);
		sender.getConfiguration().setInteger(BinaryRoundRobinSubtaskIndexSender.CONFIG_KEY, PARALLELISM);
		sender.setParallelism(PARALLELISM);

		final JobVertex pipelinedReceiver = new JobVertex("Pipelined Receiver");
		pipelinedReceiver.setInvokableClass(SlotCountExceedingParallelismTest.SubtaskIndexReceiver.class);
		pipelinedReceiver.getConfiguration().setInteger(CONFIG_KEY, PARALLELISM);
		pipelinedReceiver.setParallelism(PARALLELISM);

		pipelinedReceiver.connectNewDataSetAsInput(
				sender,
				DistributionPattern.ALL_TO_ALL,
				ResultPartitionType.PIPELINED);

		final JobVertex blockingReceiver = new JobVertex("Blocking Receiver");
		blockingReceiver.setInvokableClass(SlotCountExceedingParallelismTest.SubtaskIndexReceiver.class);
		blockingReceiver.getConfiguration().setInteger(CONFIG_KEY, PARALLELISM);
		blockingReceiver.setParallelism(PARALLELISM);

		blockingReceiver.connectNewDataSetAsInput(sender,
				DistributionPattern.ALL_TO_ALL,
				ResultPartitionType.BLOCKING);

		SlotSharingGroup slotSharingGroup = new SlotSharingGroup(
				sender.getID(), pipelinedReceiver.getID(), blockingReceiver.getID());

		sender.setSlotSharingGroup(slotSharingGroup);
		pipelinedReceiver.setSlotSharingGroup(slotSharingGroup);
		blockingReceiver.setSlotSharingGroup(slotSharingGroup);

		final JobGraph jobGraph = new JobGraph(
				"Mixed pipelined and blocking result",
				sender,
				pipelinedReceiver,
				blockingReceiver);

		flink.submitJobAndWait(jobGraph, false, TestingUtils.TESTING_DURATION());
	}

	// ---------------------------------------------------------------------------------------------

	public static class BinaryRoundRobinSubtaskIndexSender extends AbstractInvokable {

		public static final String CONFIG_KEY = "number-of-times-to-send";

		public BinaryRoundRobinSubtaskIndexSender(Environment environment) {
			super(environment);
		}

		@Override
		public void invoke() throws Exception {
			List<RecordWriter<IntValue>> writers = Lists.newArrayListWithCapacity(2);

			// The order of intermediate result creation in the job graph specifies which produced
			// result partition is pipelined/blocking.
			final RecordWriter<IntValue> pipelinedWriter =
					new RecordWriter<>(getEnvironment().getWriter(0));

			final RecordWriter<IntValue> blockingWriter =
					new RecordWriter<>(getEnvironment().getWriter(1));

			writers.add(pipelinedWriter);
			writers.add(blockingWriter);

			final int numberOfTimesToSend = getTaskConfiguration().getInteger(CONFIG_KEY, 0);

			final IntValue subtaskIndex = new IntValue(
					getEnvironment().getTaskInfo().getIndexOfThisSubtask());

			// Produce the first intermediate result and then the second in a serial fashion.
			for (RecordWriter<IntValue> writer : writers) {
				try {
					for (int i = 0; i < numberOfTimesToSend; i++) {
						writer.emit(subtaskIndex);
					}
					writer.flushAll();
				}
				finally {
					writer.clearBuffers();
				}
			}
		}
	}
}
