package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.contracts.requireThat
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.training.state.IOUState

import net.corda.training.contract.IOUContract
import net.corda.core.node.services.vault.QueryCriteria
import net.corda.core.node.services.Vault
import net.corda.core.node.services.Vault.Page
import net.corda.core.contracts.LinearState
import net.corda.core.node.services.queryBy
import net.corda.core.contracts.StateAndContract
import net.corda.core.contracts.StateAndRef
import java.security.PublicKey
import net.corda.core.contracts.Command
import net.corda.core.identity.CordaX500Name
/**
 * This is the flow which handles transfers of existing IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUTransferFlow(val linearId: UniqueIdentifier, val newLender: Party): FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        //valut query
        val criteria = QueryCriteria.LinearStateQueryCriteria(linearId = listOf(linearId))
        val result = serviceHub.vaultService.queryBy<IOUState>(criteria)
        //states
        val inStateAndRef: StateAndRef<IOUState> = result.states.single()
        val inState: IOUState = inStateAndRef.state.data
        val newState: IOUState = inState.copy().withNewLender(newLender)
        //check initated by current lender
        if (inState.lender !=  ourIdentity) 
            throw IllegalArgumentException("flow must be initiated by the current lender")

       //command
        val commandData: IOUContract.Commands.Transfer = IOUContract.Commands.Transfer()
        val requiredSigners: List<PublicKey> = ( inState.participants.map { it.owningKey }.toSet() + newState.participants.map { it.owningKey }.toSet() ).toList()
        val myCommand: Command<IOUContract.Commands.Transfer> = Command(commandData, requiredSigners)
        //notary
        //val notaryName: CordaX500Name = CordaX500Name("Notary","London","GB")
        val specificNotary: Party = inStateAndRef.state.notary
        //txbuilder
        val newStateAndContract: StateAndContract = StateAndContract(newState, IOUContract.IOU_CONTRACT_ID)
        val txBuilder: TransactionBuilder = TransactionBuilder(specificNotary)
        txBuilder.withItems(inStateAndRef, newStateAndContract, myCommand)
        txBuilder.verify(serviceHub)
        // Placeholder code to avoid type error when running the tests. Remove before starting the flow task!
        return serviceHub.signInitialTransaction(txBuilder)
    }
}

/**
 * This is the flow which signs IOU transfers.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUTransferFlow::class)
class IOUTransferFlowResponder(val flowSession: FlowSession): FlowLogic<Unit>() {
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
