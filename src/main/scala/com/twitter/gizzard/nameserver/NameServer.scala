package com.twitter.gizzard.nameserver

import java.util.TreeMap
import scala.collection.mutable
import com.twitter.xrayspecs.Time
import com.twitter.querulous.StatsCollector
import com.twitter.querulous.evaluator.QueryEvaluatorFactory
import net.lag.configgy.ConfigMap
import net.lag.logging.{Logger, ThrottledLogger}
import shards._


class NonExistentShard extends ShardException("Shard does not exist")
class InvalidShard extends ShardException("Shard has invalid attributes (such as hostname)")

object NameServer {
  /**
   * nameserver (inherit="db") {
   *   mapping = "byte_swapper"
   *   replicas {
   *     ns1 (inherit="db") {
   *       hostname = "nameserver1"
   *       database = "shards"
   *     }
   *     ns2 (inherit="db") {
   *       hostname = "nameserver2"
   *       database = "shards"
   *     }
   *   }
   * }
   */
  def apply[S <: shards.Shard](config: ConfigMap, stats: Option[StatsCollector],
                               shardRepository: ShardRepository[S],
                               log: ThrottledLogger[String],
                               replicationFuture: Future): NameServer[S] = {
    val queryEvaluatorFactory = QueryEvaluatorFactory.fromConfig(config, stats)

    val replicaConfig = config.configMap("replicas")
    val replicas = replicaConfig.keys.map { key =>
      new SqlShard(queryEvaluatorFactory(replicaConfig.configMap(key)))
    }.collect

    val shardInfo = new ShardInfo("com.twitter.gizzard.nameserver.ReplicatingShard", "", "")
    val loadBalancer = new LoadBalancer(replicas)
    val shard = new ReadWriteShardAdapter(
      new ReplicatingShard(shardInfo, 0, replicas, loadBalancer, log, replicationFuture))

    val mappingFunction: (Long => Long) = config.getString("mapping") match {
      case None =>
        { n => n }
      case Some("byte_swapper") =>
        ByteSwapper
      case Some("identity") =>
        { n => n }
    }
    new NameServer(shard, shardRepository, mappingFunction)
  }
}

class NameServer[S <: shards.Shard](nameServerShard: Shard, shardRepository: ShardRepository[S],
                                    mappingFunction: Long => Long)
  extends Shard {

  val children = List()
  val shardInfo = new ShardInfo("com.twitter.gizzard.nameserver.NameServer", "", "")
  val weight = 1 // hardcode for now
  val RETRIES = 5

  @volatile protected var shardInfos = mutable.Map.empty[ShardId, ShardInfo]
  @volatile private var familyTree: scala.collection.Map[ShardId, Seq[LinkInfo]] = null
  @volatile private var forwardings: scala.collection.Map[Int, TreeMap[Long, ShardInfo]] = null

  def createShard(shardInfo: ShardInfo) { createShard(shardInfo, RETRIES) }

  def createShard(shardInfo: ShardInfo, retries: Int) {
    try {
      nameServerShard.createShard(shardInfo, shardRepository)
    } catch {
      case e: InvalidShard if (retries > 0) =>
        // allow conflicts on the id generator
        createShard(shardInfo, retries - 1)
    }
  }

  def getShardInfo(id: ShardId) = shardInfos(id)

  def getChildren(id: ShardId) = {
    familyTree.getOrElse(id, new mutable.ArrayBuffer[LinkInfo])
  }

  def reload() {
    nameServerShard.reload()

    val newShardInfos = mutable.Map.empty[ShardId, ShardInfo]
    nameServerShard.listShards().foreach { shardInfo =>
      newShardInfos += (shardInfo.id -> shardInfo)
    }

    val newFamilyTree = new mutable.HashMap[ShardId, mutable.ArrayBuffer[LinkInfo]]
    nameServerShard.listLinks().foreach { link =>
      val children = newFamilyTree.getOrElseUpdate(link.upId, new mutable.ArrayBuffer[LinkInfo])
      children += link
    }

    val newForwardings = new mutable.HashMap[Int, TreeMap[Long, ShardInfo]]
    nameServerShard.getForwardings().foreach { forwarding =>
      val treeMap = newForwardings.getOrElseUpdate(forwarding.tableId, new TreeMap[Long, ShardInfo])
      treeMap.put(forwarding.baseId, newShardInfos.getOrElse(forwarding.shardId, throw new NonExistentShard))
    }

    shardInfos = newShardInfos
    familyTree = newFamilyTree
    forwardings = newForwardings
  }

  def findShardById(id: ShardId, weight: Int): S = {
    val shardInfo = getShardInfo(id)
    val children = getChildren(id).map { linkInfo =>
      findShardById(linkInfo.downId, linkInfo.weight)
    }.toList
    shardRepository.find(shardInfo, weight, children)
  }

  @throws(classOf[NonExistentShard])
  def findShardById(id: ShardId): S = findShardById(id, 1)

  def findCurrentForwarding(tableId: Int, id: Long) = {
    val shardInfo = forwardings.get(tableId).flatMap { bySourceIds =>
      val item = bySourceIds.floorEntry(mappingFunction(id))
      if (item != null) {
        Some(item.getValue)
      } else {
        None
      }
    } getOrElse {
      throw new NonExistentShard
    }

    findShardById(shardInfo.id)
  }

  def createShard[S <: shards.Shard](shardInfo: ShardInfo, repository: ShardRepository[S]) = nameServerShard.createShard(shardInfo, repository)
  def getShard(id: ShardId) = nameServerShard.getShard(id)
  def deleteShard(id: ShardId) = nameServerShard.deleteShard(id)
  def addLink(upId: ShardId, downId: ShardId, weight: Int) = nameServerShard.addLink(upId, downId, weight)
  def removeLink(upId: ShardId, downId: ShardId) = nameServerShard.removeLink(upId, downId)
  def listUpwardLinks(id: ShardId) = nameServerShard.listUpwardLinks(id)
  def listDownwardLinks(id: ShardId) = nameServerShard.listDownwardLinks(id)
  def listLinks() = nameServerShard.listLinks()
  def markShardBusy(id: ShardId, busy: Busy.Value) = nameServerShard.markShardBusy(id, busy)
  def setForwarding(forwarding: Forwarding) = nameServerShard.setForwarding(forwarding)
  def replaceForwarding(oldId: ShardId, newId: ShardId) = nameServerShard.replaceForwarding(oldId, newId)
  def getForwarding(tableId: Int, baseId: Long) = nameServerShard.getForwarding(tableId, baseId)
  def getForwardingForShard(id: ShardId) = nameServerShard.getForwardingForShard(id)
  def getForwardings() = nameServerShard.getForwardings()
  def shardsForHostname(hostname: String) = nameServerShard.shardsForHostname(hostname)
  def listShards() = nameServerShard.listShards()
  def getBusyShards() = nameServerShard.getBusyShards()
  def getChildShardsOfClass(parentId: ShardId, className: String) = nameServerShard.getChildShardsOfClass(parentId, className)
  def rebuildSchema() = nameServerShard.rebuildSchema()
}
