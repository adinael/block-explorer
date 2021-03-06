package com.xsn.explorer.services

import com.alexitc.playsonify.core.FutureOr.Implicits.{FutureListOps, FutureOps, OrOps}
import com.alexitc.playsonify.core.{FutureApplicationResult, FutureOr}
import com.xsn.explorer.errors.{InvalidRawTransactionError, TransactionFormatError, TransactionNotFoundError, XSNWorkQueueDepthExceeded}
import com.xsn.explorer.models.rpc.TransactionVIN
import com.xsn.explorer.models.{HexString, Transaction, TransactionDetails, TransactionId, TransactionValue}
import com.xsn.explorer.util.Extensions.FutureOrExt
import javax.inject.Inject
import org.scalactic.{Bad, Good, One, Or}
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, JsString, JsValue}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class TransactionRPCService @Inject() (
    xsnService: XSNService)(
    implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def getRawTransaction(txidString: String): FutureApplicationResult[JsValue] = {
    val result = for {
      txid <- {
        val maybe = TransactionId.from(txidString)
        Or.from(maybe, One(TransactionFormatError)).toFutureOr
      }

      transaction <- xsnService.getRawTransaction(txid).toFutureOr
    } yield transaction

    result.toFuture
  }

  def getTransactionDetails(txidString: String): FutureApplicationResult[TransactionDetails] = {
    val result = for {
      txid <- {
        val maybe = TransactionId.from(txidString)
        Or.from(maybe, One(TransactionFormatError)).toFutureOr
      }

      transaction <- xsnService.getTransaction(txid).toFutureOr

      input <- transaction
          .vin
          .map(getTransactionValue)
          .toFutureOr
    } yield TransactionDetails.from(transaction, input)

    result.toFuture
  }

  def getTransaction(txid: TransactionId): FutureApplicationResult[Transaction] = {
    val result = for {
      tx <- xsnService.getTransaction(txid).toFutureOr
      transactionVIN <- getTransactionVIN(tx.vin).toFutureOr
      rpcTransaction = tx.copy(vin = transactionVIN)
    } yield Transaction.fromRPC(rpcTransaction)

    result.toFuture
  }

  private def getTransactionVIN(list: List[TransactionVIN]): FutureApplicationResult[List[TransactionVIN]] = {
    def getVIN(vin: TransactionVIN) = {
      getTransactionValue(vin)
          .map {
            case Good(transactionValue) =>
              val newVIN = vin.copy(address = Some(transactionValue.address), value = Some(transactionValue.value))
              Good(newVIN)

            case Bad(e) => Bad(e)
          }
    }

    def loadVINSequentially(pending: List[TransactionVIN]): FutureOr[List[TransactionVIN]] = pending match {
      case x :: xs =>
        for {
          tx <- getVIN(x).toFutureOr
          next <- loadVINSequentially(xs)
        } yield tx :: next

      case _ => Future.successful(Good(List.empty)).toFutureOr
    }

    list
        .map(getVIN)
        .toFutureOr
        .toFuture
        .recoverWith {
          case NonFatal(ex) =>
            logger.warn(s"Failed to load VIN, trying sequentially, error = ${ex.getMessage}")
            loadVINSequentially(list).toFuture
        }
  }

  def getTransactions(ids: List[TransactionId]): FutureApplicationResult[List[Transaction]] = {
    def loadTransactionsSlowly(pending: List[TransactionId]): FutureOr[List[Transaction]] = pending match {
      case x :: xs =>
        for {
          tx <- getTransaction(x).toFutureOr
          next <- loadTransactionsSlowly(xs)
        } yield tx :: next

      case _ => Future.successful(Good(List.empty)).toFutureOr
    }

    ids
        .map(getTransaction)
        .toFutureOr
        .recoverWith(XSNWorkQueueDepthExceeded) {
          logger.warn("Unable to load transaction due to server overload, loading them slowly")
          loadTransactionsSlowly(ids)
        }
        .toFuture
        .recoverWith {
          case NonFatal(ex) =>
            logger.warn(s"Unable to load transactions due to server error, loading them sequentially, error = ${ex.getMessage}")
            loadTransactionsSlowly(ids).toFuture
        }
  }

  def sendRawTransaction(hexString: String): FutureApplicationResult[JsValue] = {
    val result = for {
      hex <- Or.from(HexString.from(hexString), One(InvalidRawTransactionError)).toFutureOr
      _ <- xsnService.sendRawTransaction(hex).toFutureOr
    } yield JsObject.empty + ("hex" -> JsString(hex.string))

    result.toFuture
  }

  private def getTransactionValue(vin: TransactionVIN): FutureApplicationResult[TransactionValue] = {
    val valueMaybe = for {
      value <- vin.value
      address <- vin.address
    } yield TransactionValue(address, value)

    valueMaybe
        .map(Good(_))
        .map(Future.successful)
        .getOrElse {
          val txid = vin.txid

          val result = for {
            tx <- xsnService.getTransaction(txid).toFutureOr
            r <- {
              val maybe = tx
                  .vout
                  .find(_.n == vin.voutIndex)
                  .flatMap(TransactionValue.from)

              Or.from(maybe, One(TransactionNotFoundError)).toFutureOr
            }
          } yield r

          result.toFuture
        }
  }
}
