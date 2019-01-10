import { Component, OnInit } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';

import { TranslateService } from '@ngx-translate/core';

import { Transaction, TransactionValue } from '../../models/transaction';

import { ErrorService } from '../../services/error.service';
import { NavigatorService } from '../../services/navigator.service';
import { TransactionsService } from '../../services/transactions.service';

@Component({
  selector: 'app-transaction-details',
  templateUrl: './transaction-details.component.html',
  styleUrls: ['./transaction-details.component.css']
})
export class TransactionDetailsComponent implements OnInit {

  transaction: Transaction;

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private navigatorService: NavigatorService,
    private transactionsService: TransactionsService,
    private errorService: ErrorService) { }

  ngOnInit() {
    this.route.params.forEach(params => this.onTransactionId(params['txid']));
  }

  private onTransactionId(txid: string) {
    this.transactionsService.get(txid).subscribe(
      response => this.onTransactionRetrieved(response),
      response => this.onError(response)
    );
  }

  private onTransactionRetrieved(response: Transaction) {
    this.transaction = response;
    this.transaction.input = this.collapseRepeatedRows(this.transaction.input);
    this.transaction.output = this.collapseRepeatedRows(this.transaction.output);
  }

  private onError(response: any) {
    this.errorService.renderServerErrors(null, response);
  }

  private collapseRepeatedRows(rows: TransactionValue[]): TransactionValue[] {
    if (rows.length === 0) {
      return rows;
    }

    rows = rows.sort((a, b) => {
      return a.address < b.address ? -1 : 1;
    });
    const collapsedRows = [];
    let accumulatedTransaction = new TransactionValue();
    accumulatedTransaction.address = rows[ 0 ].address;
    accumulatedTransaction.value = 0.00;
    let countSameAddress = 0;

    rows.forEach((row) => {
      const aNewAddressTransaction = row.address !== accumulatedTransaction.address;

      if (aNewAddressTransaction) {
        this.pushAccumulatedTransaction(collapsedRows, accumulatedTransaction, countSameAddress);

        accumulatedTransaction = new TransactionValue();
        accumulatedTransaction.address = row.address;
        accumulatedTransaction.value = 0.00;
      }

      countSameAddress = aNewAddressTransaction ? 1 : countSameAddress + 1;
      accumulatedTransaction.value += row.value;
    });
    this.pushAccumulatedTransaction(collapsedRows, accumulatedTransaction, countSameAddress);

    return collapsedRows;
  }

  private pushAccumulatedTransaction(collapsedRows: TransactionValue[], collapsedRow: TransactionValue, count: number): void {
    if (count > 1) {
      collapsedRow.address += ` (${ count })`;
    }

    collapsedRows.push(collapsedRow);
  }

  getFee(tx: Transaction): number {
    const vout = tx.output.map(t => t.value).reduce((a, b) => a + b, 0);
    return Math.max(0, this.getVIN(tx) - vout);
  }

  private getVIN(tx): number {
    if (tx.input == null || tx.input.length === 0) {
      return 0;
    } else {
      return tx.input.map(t => t.value).reduce((a, b) => a + b, 0);
    }
  }
}
