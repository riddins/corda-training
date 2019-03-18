package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.requireThat
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.state.IOUState

import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.training.contract.IOUContract
import net.corda.core.contracts.Command
import net.corda.core.contracts.StateAndContract

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(val state: IOUState) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        val notaryName: CordaX500Name = CordaX500Name("Notary","London","GB")
        val commandData: IOUContract.Commands.Issue = IOUContract.Commands.Issue()
        val ourCommand: Command<IOUContract.Commands.Issue> = Command(commandData, state.participants.map { it.owningKey }.toList() )
        val specificNotary: Party = serviceHub.networkMapCache.getNotary(notaryName)!!
        val txBuilder: TransactionBuilder = TransactionBuilder(specificNotary)
        val ourOutput: StateAndContract = StateAndContract(state, IOUContract.IOU_CONTRACT_ID)
        txBuilder.withItems(ourOutput, ourCommand)
        txBuilder.verify(serviceHub)
        val ptx: SignedTransaction = serviceHub.signInitialTransaction(txBuilder)
        val participantFlowSessions: List<FlowSession> = (state.participants - ourIdentity).map { initiateFlow(it) }.toList()
        val stx: SignedTransaction = subFlow(CollectSignaturesFlow(ptx, participantFlowSessions))
        return subFlow(FinalityFlow(stx))

    }
}

/**
 * This is the flow which signs IOU issuances.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }
        subFlow(signedTransactionFlow)
    }
}
