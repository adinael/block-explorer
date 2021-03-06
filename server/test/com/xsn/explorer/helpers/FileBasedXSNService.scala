package com.xsn.explorer.helpers

import com.alexitc.playsonify.core.FutureApplicationResult
import com.xsn.explorer.errors.{BlockNotFoundError, TransactionNotFoundError}
import com.xsn.explorer.models.rpc.{Block, Transaction}
import com.xsn.explorer.models.{Blockhash, Height, TransactionId}
import org.scalactic.{Good, One, Or}
import play.api.libs.json.JsValue

import scala.concurrent.Future

class FileBasedXSNService extends DummyXSNService {

  private lazy val blockMap = BlockLoader.all().map { block => block.hash -> block }.toMap
  private lazy val transactionMap = TransactionLoader.all().map { tx => tx.id -> tx }.toMap

  override def getBlock(blockhash: Blockhash): FutureApplicationResult[Block] = {
    val maybe = blockMap.get(blockhash)
    val result = Or.from(maybe, One(BlockNotFoundError))
    Future.successful(result)
  }

  override def getRawBlock(blockhash: Blockhash): FutureApplicationResult[JsValue] = {
    val result = BlockLoader.json(blockhash.string)
    Future.successful(Good(result))
  }

  override def getBlockhash(height: Height): FutureApplicationResult[Blockhash] = {
    val maybe = blockMap.collectFirst {
      case (_, block) if block.height == height => block.hash
    }

    val result = Or.from(maybe, One(BlockNotFoundError))
    Future.successful(result)
  }

  override def getLatestBlock(): FutureApplicationResult[Block] = {
    val block = blockMap.values.maxBy(_.height.int)
    Future.successful(Good(block))
  }

  override def getTransaction(txid: TransactionId): FutureApplicationResult[Transaction] = {
    val maybe = transactionMap.get(txid)
    val result = Or.from(maybe, One(TransactionNotFoundError))
    Future.successful(result)
  }
}
