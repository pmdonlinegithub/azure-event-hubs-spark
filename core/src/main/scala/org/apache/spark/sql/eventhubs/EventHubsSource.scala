/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.eventhubs

import java.io._
import java.nio.charset.StandardCharsets

import org.apache.commons.io.IOUtils
import org.apache.spark.SparkContext
import org.apache.spark.eventhubs.rdd.{ EventHubsRDD, OffsetRange }
import org.apache.spark.eventhubs.{ EventHubsConf, NameAndPartition, _ }
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.ExecutorCacheTaskLocation
import org.apache.spark.sql.execution.streaming.{
  HDFSMetadataLog,
  Offset,
  SerializedOffset,
  Source
}
import org.apache.spark.sql.types._
import org.apache.spark.sql.{ DataFrame, SQLContext }

/**
 * A [[Source]] that reads data from Event Hubs.
 *
 * When the [[EventHubsSource]] is first created, the initial
 * starting positions must be determined. This is done in the
 * initialPartitionSeqNos. If checkpoints are present, then the
 * appropriate [[EventHubsSourceOffset]] is found and used. If not,
 * the user-provided [[EventHubsConf]] is queried for starting positions.
 * If none have been set, then we start from the latest sequence
 * numbers available in each partition.
 *
 * `getOffset()` queries the Event Hubs service to determine the latest
 * sequence numbers available in each partition. These are returned to
 * Spark as [[EventHubsSourceOffset]]s. We also retreive the earliest
 * sequence numbers in each partition to ensure that events we intend
 * to receive are present in the service (e.g. make sure they haven't
 * aged out).
 *
 * `getBatch()` returns a DF that reads from the start sequence numbers
 * to the end sequence numbers for each partition. Start sequence numbers
 * are inclusive and end sequence numbers are exclusive.
 *
 * The DF returned is made from the [[EventHubsRDD]] which is generated
 * such that each partition is generated by the same executors across
 * all batches. That way receivers are cached and reused efficiently.
 * This allows events to be prefetched before they're needed by Spark.
 */
private[spark] class EventHubsSource private[eventhubs] (sqlContext: SQLContext,
                                                         parameters: Map[String, String],
                                                         metadataPath: String)
    extends Source
    with Logging {

  import EventHubsConf._
  import EventHubsSource._

  private lazy val ehClient = EventHubsSourceProvider.clientFactory(parameters)(ehConf)
  private lazy val partitionCount: Int = ehClient.partitionCount

  private val ehConf = EventHubsConf.toConf(parameters)
  private val ehName = ehConf.name

  private val sc = sqlContext.sparkContext

  private val maxOffsetsPerTrigger: Option[Long] =
    Option(parameters.get(MaxEventsPerTriggerKey).map(_.toLong).getOrElse(partitionCount * 1000))

  private lazy val initialPartitionSeqNos = {
    val metadataLog =
      new HDFSMetadataLog[EventHubsSourceOffset](sqlContext.sparkSession, metadataPath) {
        override def serialize(metadata: EventHubsSourceOffset, out: OutputStream): Unit = {
          out.write(0) // SPARK-19517
          val writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))
          writer.write("v" + VERSION + "\n")
          writer.write(metadata.json)
          writer.flush()
        }

        override def deserialize(in: InputStream): EventHubsSourceOffset = {
          in.read() // zero byte is read (SPARK-19517)
          val content = IOUtils.toString(new InputStreamReader(in, StandardCharsets.UTF_8))
          // HDFSMetadataLog guarantees that it never creates a partial file.
          assert(content.length != 0)
          if (content(0) == 'v') {
            val indexOfNewLine = content.indexOf("\n")
            if (indexOfNewLine > 0) {
              val version =
                parseLogVersion(content.substring(0, indexOfNewLine), VERSION)
              EventHubsSourceOffset(SerializedOffset(content.substring(indexOfNewLine + 1)))
            } else {
              throw new IllegalStateException("Log file was malformed.")
            }
          } else {
            EventHubsSourceOffset(SerializedOffset(content)) // Spark 2.1 log file
          }
        }

        private def parseLogVersion(text: String, maxSupportedVersion: Int): Int = {
          if (text.length > 0 && text(0) == 'v') {
            val version =
              try {
                text.substring(1, text.length).toInt
              } catch {
                case _: NumberFormatException =>
                  throw new IllegalStateException(s"Log file was malformed: failed to read correct log " +
                    s"version from $text.")
              }
            if (version > 0) {
              if (version > maxSupportedVersion) {
                throw new IllegalStateException(s"UnsupportedLogVersion: maximum supported log version " +
                  s"is v${maxSupportedVersion}, but encountered v$version. The log file was produced " +
                  s"by a newer version of Spark and cannot be read by this version. Please upgrade.")
              } else {
                return version
              }
            }
          }
          // reaching here means we failed to read the correct log version
          throw new IllegalStateException(s"Log file was malformed: failed to read correct log " +
            s"version from $text.")
        }
      }

    metadataLog
      .get(0)
      .getOrElse {
        // translate starting points within ehConf to sequence numbers
        val seqNos = ehClient.translate(ehConf, partitionCount).map {
          case (pId, seqNo) =>
            (NameAndPartition(ehName, pId), seqNo)
        }
        val offset = EventHubsSourceOffset(seqNos)
        metadataLog.add(0, offset)
        logInfo(s"Initial sequence numbers: $seqNos")
        offset
      }
      .partitionToSeqNos
  }

  private var currentSeqNos: Option[Map[NameAndPartition, SequenceNumber]] = None

  private var earliestSeqNos: Option[Map[NameAndPartition, SequenceNumber]] = None

  override def schema: StructType = EventHubsSourceProvider.eventHubsSchema

  override def getOffset: Option[Offset] = {
    // Make sure initialPartitionSeqNos is initialized
    initialPartitionSeqNos

    // This contains an array of the following elements:
    // (partition, (earliestSeqNo, latestSeqNo)
    val earliestAndLatest = ehClient.allBoundedSeqNos

    // There is a possibility that data from EventHubs will
    // expire before it can be consumed from Spark. We collect
    // the earliest sequence numbers available in the service
    // here. In getBatch, we'll make sure our starting sequence
    // numbers are greater than or equal to the earliestSeqNos.
    // If not, we'll report possible data loss.
    earliestSeqNos = Some(earliestAndLatest.map {
      case (p, (e, _)) => NameAndPartition(ehName, p) -> e
    }.toMap)

    val latest = earliestAndLatest.map {
      case (p, (_, l)) => NameAndPartition(ehName, p) -> l
    }.toMap

    val seqNos: Map[NameAndPartition, SequenceNumber] = maxOffsetsPerTrigger match {
      case None =>
        latest

      case Some(limit) if currentSeqNos.isEmpty =>
        val startingSeqNos = adjustStartingOffset(initialPartitionSeqNos)
        rateLimit(limit, startingSeqNos, latest, earliestSeqNos.get)

      case Some(limit) =>
        val startingSeqNos = adjustStartingOffset(currentSeqNos.get)
        rateLimit(limit, startingSeqNos, latest, earliestSeqNos.get)
    }

    currentSeqNos = Some(seqNos)
    logInfo(s"GetOffset: ${seqNos.toSeq.map(_.toString).sorted}")

    Some(EventHubsSourceOffset(seqNos))
  }

  private def adjustStartingOffset(
      from: Map[NameAndPartition, SequenceNumber]): Map[NameAndPartition, SequenceNumber] = {
    from.map {
      case (nAndP, seqNo) =>
        if (seqNo < earliestSeqNos.get(nAndP)) {
          reportDataLoss(
            s"Starting seqNo $seqNo in partition ${nAndP.partitionId} of EventHub ${nAndP.ehName} " +
              s"is behind the earliest sequence number ${earliestSeqNos.get(nAndP)} " +
              s"present in the service. Some events may have expired and been missed.")
          nAndP -> earliestSeqNos.get(nAndP)
        } else {
          nAndP -> seqNo
        }
    }
  }

  /** Proportionally distribute limit number of offsets among partitions */
  private def rateLimit(
      limit: Long,
      from: Map[NameAndPartition, SequenceNumber],
      until: Map[NameAndPartition, SequenceNumber],
      fromNew: Map[NameAndPartition, SequenceNumber]): Map[NameAndPartition, SequenceNumber] = {
    val sizes = until.flatMap {
      case (nameAndPartition, end) =>
        // If begin isn't defined, something's wrong, but let alert logic in getBatch handle it
        from.get(nameAndPartition).orElse(fromNew.get(nameAndPartition)).flatMap { begin =>
          val size = end - begin
          logDebug(s"rateLimit $nameAndPartition size is $size")
          if (size > 0) Some(nameAndPartition -> size) else None
        }
    }
    val total = sizes.values.sum.toDouble
    if (total < 1) {
      until
    } else {
      until.map {
        case (nameAndPartition, end) =>
          nameAndPartition -> sizes
            .get(nameAndPartition)
            .map { size =>
              val begin = from.getOrElse(nameAndPartition, fromNew(nameAndPartition))
              val prorate = limit * (size / total)
              logDebug(s"rateLimit $nameAndPartition prorated amount is $prorate")
              // Don't completely starve small partitions
              val off = begin + (if (prorate < 1) Math.ceil(prorate) else Math.floor(prorate)).toLong
              logDebug(s"rateLimit $nameAndPartition new offset is $off")
              // Paranoia, make sure not to return an offset that's past end
              Math.min(end, off)
            }
            .getOrElse(end)
      }
    }
  }

  /**
   * Returns a [[DataFrame]] containing events from EventHubs between
   * the start and end [[Offset]]s provided.
   *
   * @param start the start positions (inclusive)
   * @param end   the end positions (exclusive)
   * @return the [[DataFrame]] with Event Hubs data
   */
  override def getBatch(start: Option[Offset], end: Offset): DataFrame = {
    initialPartitionSeqNos

    logInfo(s"getBatch called with start = $start and end = $end")
    val untilSeqNos = EventHubsSourceOffset.getPartitionSeqNos(end)
    // On recovery, getBatch wil be called before getOffset
    if (currentSeqNos.isEmpty) {
      currentSeqNos = Some(untilSeqNos)
    }
    if (start.isDefined && start.get == end) {
      return sqlContext.internalCreateDataFrame(sqlContext.sparkContext.emptyRDD,
                                                schema,
                                                isStreaming = true)
    }

    if (earliestSeqNos.isEmpty) {
      val earliestAndLatest = ehClient.allBoundedSeqNos
      earliestSeqNos = Some(earliestAndLatest.map {
        case (p, (e, _)) => NameAndPartition(ehName, p) -> e
      }.toMap)
    }

    val fromSeqNos = start match {
      // recovery mode ..
      case Some(prevBatchEndOffset) =>
        val startingSeqNos = EventHubsSourceOffset.getPartitionSeqNos(prevBatchEndOffset)
        adjustStartingOffset(startingSeqNos)

      case None => adjustStartingOffset(initialPartitionSeqNos)
    }

    val nameAndPartitions = untilSeqNos.keySet.toSeq
    logDebug("Partitions: " + nameAndPartitions.mkString(", "))
    val sortedExecutors = getSortedExecutorList(sc)
    val numExecutors = sortedExecutors.length
    logDebug("Sorted executors: " + sortedExecutors.mkString(", "))

    // Calculate offset ranges
    val offsetRanges = (for {
      np <- nameAndPartitions
      fromSeqNo = fromSeqNos.getOrElse(
        np,
        throw new IllegalStateException(s"$np doesn't have a fromSeqNo"))

      untilSeqNo = untilSeqNos(np)
      preferredPartitionLocation = ehConf.partitionPreferredLocationStrategy match {
        case PartitionPreferredLocationStrategy.Hash => np.hashCode
        case PartitionPreferredLocationStrategy.BalancedHash =>
          np.ehName.hashCode() + np.partitionId
        case _ =>
          throw new IllegalArgumentException(
            "Unsupported partition strategy: " +
              ehConf.partitionPreferredLocationStrategy)
      }
      preferredLoc = if (numExecutors > 0) {
        Some(sortedExecutors(Math.floorMod(preferredPartitionLocation, numExecutors)))
      } else None
    } yield OffsetRange(np, fromSeqNo, untilSeqNo, preferredLoc)).filter { range =>
      if (range.untilSeqNo < range.fromSeqNo) {
        reportDataLoss(
          s"Partition ${range.nameAndPartition}'s sequence number was changed from " +
            s"${range.fromSeqNo} to ${range.untilSeqNo}, some data may have been missed")
        false
      } else {
        true
      }
    }.toArray

    val rdd =
      EventHubsSourceProvider.toInternalRow(new EventHubsRDD(sc, ehConf.trimmed, offsetRanges))
    logInfo(
      "GetBatch generating RDD of offset range: " +
        offsetRanges.sortBy(_.nameAndPartition.toString).mkString(", "))
    sqlContext.internalCreateDataFrame(rdd, schema, isStreaming = true)
  }

  /**
   * Stop this source and any resources it has allocated
   */
  override def stop(): Unit = synchronized {
    ehClient.close()
  }

  /**
   * Logs a warning when data may have been missed.
   */
  private def reportDataLoss(message: String): Unit = {
    logWarning(message + s". $InstructionsForPotentialDataLoss")
  }
}

/**
 * Companion object for [[EventHubsSource]].
 */
private[eventhubs] object EventHubsSource {
  val InstructionsForPotentialDataLoss: String =
    """
      |Some data may have been lost because they are not available in EventHubs any more; either the
      | data was aged out by EventHubs or the EventHubs instance may have been deleted before all the data in the
      | instance was processed.
    """.stripMargin

  private[eventhubs] val VERSION = 1

  def getSortedExecutorList(sc: SparkContext): Array[String] = {
    val bm = sc.env.blockManager
    bm.master
      .getPeers(bm.blockManagerId)
      .toArray
      .map(x => ExecutorCacheTaskLocation(x.host, x.executorId))
      .sortWith(compare)
      .map(_.toString)
  }

  private def compare(a: ExecutorCacheTaskLocation, b: ExecutorCacheTaskLocation): Boolean = {
    if (a.host == b.host) {
      a.executorId > b.executorId
    } else {
      a.host > b.host
    }
  }
}
