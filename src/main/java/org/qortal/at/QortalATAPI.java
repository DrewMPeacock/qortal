package org.qortal.at;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import org.ciyam.at.API;
import org.ciyam.at.ExecutionException;
import org.ciyam.at.FunctionData;
import org.ciyam.at.IllegalFunctionCodeException;
import org.ciyam.at.MachineState;
import org.ciyam.at.OpCode;
import org.ciyam.at.Timestamp;
import org.qortal.account.Account;
import org.qortal.account.GenesisAccount;
import org.qortal.account.PublicKeyAccount;
import org.qortal.asset.Asset;
import org.qortal.block.Block;
import org.qortal.block.BlockChain;
import org.qortal.block.BlockChain.CiyamAtSettings;
import org.qortal.crypto.Crypto;
import org.qortal.data.at.ATData;
import org.qortal.data.block.BlockData;
import org.qortal.data.block.BlockSummaryData;
import org.qortal.data.transaction.ATTransactionData;
import org.qortal.data.transaction.BaseTransactionData;
import org.qortal.data.transaction.MessageTransactionData;
import org.qortal.data.transaction.PaymentTransactionData;
import org.qortal.data.transaction.TransactionData;
import org.qortal.group.Group;
import org.qortal.repository.BlockRepository;
import org.qortal.repository.DataException;
import org.qortal.repository.Repository;
import org.qortal.transaction.AtTransaction;
import org.qortal.transaction.Transaction;

import com.google.common.primitives.Bytes;

public class QortalATAPI extends API {

	// Properties
	private Repository repository;
	private ATData atData;
	private long blockTimestamp;
	private final CiyamAtSettings ciyamAtSettings;

	/** List of generated AT transactions */
	List<AtTransaction> transactions;

	// Constructors

	public QortalATAPI(Repository repository, ATData atData, long blockTimestamp) {
		this.repository = repository;
		this.atData = atData;
		this.transactions = new ArrayList<>();
		this.blockTimestamp = blockTimestamp;

		this.ciyamAtSettings = BlockChain.getInstance().getCiyamAtSettings();
	}

	// Methods specific to Qortal AT processing, not inherited

	public Repository getRepository() {
		return this.repository;
	}

	public List<AtTransaction> getTransactions() {
		return this.transactions;
	}

	public BigDecimal calcFinalFees(MachineState state) {
		return this.ciyamAtSettings.feePerStep.multiply(BigDecimal.valueOf(state.getSteps()));
	}

	// Inherited methods from CIYAM AT API

	@Override
	public int getMaxStepsPerRound() {
		return this.ciyamAtSettings.maxStepsPerRound;
	}

	@Override
	public int getOpCodeSteps(OpCode opcode) {
		if (opcode.value >= OpCode.EXT_FUN.value && opcode.value <= OpCode.EXT_FUN_RET_DAT_2.value)
			return this.ciyamAtSettings.stepsPerFunctionCall;

		return 1;
	}

	@Override
	public long getFeePerStep() {
		return this.ciyamAtSettings.feePerStep.unscaledValue().longValue();
	}

	@Override
	public int getCurrentBlockHeight() {
		try {
			return this.repository.getBlockRepository().getBlockchainHeight();
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch current blockchain height?", e);
		}
	}

	@Override
	public int getATCreationBlockHeight(MachineState state) {
		try {
			return this.repository.getATRepository().getATCreationBlockHeight(this.atData.getATAddress());
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch AT's creation block height?", e);
		}
	}

	@Override
	public void putPreviousBlockHashIntoA(MachineState state) {
		try {
			int previousBlockHeight = this.repository.getBlockRepository().getBlockchainHeight() - 1;

			// We only need signature, so only request a block summary
			List<BlockSummaryData> blockSummaries = this.repository.getBlockRepository().getBlockSummaries(previousBlockHeight, previousBlockHeight);
			if (blockSummaries == null || blockSummaries.size() != 1)
				throw new RuntimeException("AT API unable to fetch previous block hash?");

			// Block's signature is 128 bytes so we need to reduce this to 4 longs (32 bytes)
			// To be able to use hash to look up block, save height (8 bytes) and rehash with SHA192 (24 bytes)
			this.setA1(state, previousBlockHeight);

			byte[] sigHash192 = sha192(blockSummaries.get(0).getSignature());
			this.setA2(state, fromBytes(sigHash192, 0));
			this.setA3(state, fromBytes(sigHash192, 8));
			this.setA4(state, fromBytes(sigHash192, 16));
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch previous block?", e);
		}
	}

	@Override
	public void putTransactionAfterTimestampIntoA(Timestamp timestamp, MachineState state) {
		// Recipient is this AT
		String recipient = this.atData.getATAddress();

		int height = timestamp.blockHeight;
		int sequence = timestamp.transactionSequence + 1;

		BlockRepository blockRepository = this.getRepository().getBlockRepository();

		try {
			Account recipientAccount = new Account(this.getRepository(), recipient);
			int currentHeight = blockRepository.getBlockchainHeight();

			while (height <= currentHeight) {
				BlockData blockData = blockRepository.fromHeight(height);

				if (blockData == null)
					throw new DataException("Unable to fetch block " + height + " from repository?");

				Block block = new Block(this.getRepository(), blockData);

				List<Transaction> blockTransactions = block.getTransactions();

				// No more transactions in this block? Try next block
				if (sequence >= blockTransactions.size()) {
					++height;
					sequence = 0;
					continue;
				}

				Transaction transaction = blockTransactions.get(sequence);

				// Transaction needs to be sent to specified recipient
				if (transaction.getRecipientAccounts().contains(recipientAccount)) {
					// Found a transaction

					this.setA1(state, new Timestamp(height, timestamp.blockchainId, sequence).longValue());

					// Hash transaction's signature into other three A fields for future verification that it's the same transaction
					byte[] sigHash192 = sha192(transaction.getTransactionData().getSignature());
					this.setA2(state, fromBytes(sigHash192, 0));
					this.setA3(state, fromBytes(sigHash192, 8));
					this.setA4(state, fromBytes(sigHash192, 16));

					return;
				}

				// Transaction wasn't for us - keep going
				++sequence;
			}

			// No more transactions - zero A and exit
			this.zeroA(state);
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch next transaction?", e);
		}
	}

	@Override
	public long getTypeFromTransactionInA(MachineState state) {
		TransactionData transactionData = this.getTransactionFromA(state);

		switch (transactionData.getType()) {
			case PAYMENT:
				return ATTransactionType.PAYMENT.value;

			case MESSAGE:
				return ATTransactionType.MESSAGE.value;

			case AT:
				if (((ATTransactionData) transactionData).getAmount() != null)
					return ATTransactionType.PAYMENT.value;
				else
					return ATTransactionType.MESSAGE.value;

			default:
				return 0xffffffffffffffffL;
		}
	}

	@Override
	public long getAmountFromTransactionInA(MachineState state) {
		TransactionData transactionData = this.getTransactionFromA(state);

		switch (transactionData.getType()) {
			case PAYMENT:
				return ((PaymentTransactionData) transactionData).getAmount().unscaledValue().longValue();

			case AT:
				BigDecimal amount = ((ATTransactionData) transactionData).getAmount();

				if (amount != null)
					return amount.unscaledValue().longValue();

				// fall-through to default

			default:
				return 0xffffffffffffffffL;
		}
	}

	@Override
	public long getTimestampFromTransactionInA(MachineState state) {
		// Transaction's "timestamp" already stored in A1
		Timestamp timestamp = new Timestamp(state.getA1());
		return timestamp.longValue();
	}

	@Override
	public long generateRandomUsingTransactionInA(MachineState state) {
		// The plan here is to sleep for a block then use next block's signature
		// and this transaction's signature to generate pseudo-random, but deterministic, value.

		if (!isFirstOpCodeAfterSleeping(state)) {
			// First call

			// Sleep for a block
			this.setIsSleeping(state, true);

			return 0L; // not used
		} else {
			// Second call

			// HASH(A and new block hash)
			TransactionData transactionData = this.getTransactionFromA(state);

			try {
				BlockData blockData = this.repository.getBlockRepository().getLastBlock();

				if (blockData == null)
					throw new RuntimeException("AT API unable to fetch latest block?");

				byte[] input = Bytes.concat(transactionData.getSignature(), blockData.getSignature());

				byte[] hash = Crypto.digest(input);

				return fromBytes(hash, 0);
			} catch (DataException e) {
				throw new RuntimeException("AT API unable to fetch latest block from repository?", e);
			}
		}
	}

	@Override
	public void putMessageFromTransactionInAIntoB(MachineState state) {
		// Zero B in case of issues or shorter-than-B message
		this.zeroB(state);

		TransactionData transactionData = this.getTransactionFromA(state);

		byte[] messageData = null;

		switch (transactionData.getType()) {
			case MESSAGE:
				messageData = ((MessageTransactionData) transactionData).getData();
				break;

			case AT:
				messageData = ((ATTransactionData) transactionData).getMessage();
				break;

			default:
				return;
		}

		// Check data length is appropriate, i.e. not larger than B
		if (messageData.length > 4 * 8)
			return;

		// Pad messageData to fit B
		byte[] paddedMessageData = Bytes.ensureCapacity(messageData, 4 * 8, 0);

		// Endian must be correct here so that (for example) a SHA256 message can be compared to one generated locally
		this.setB(state, paddedMessageData);
	}

	@Override
	public void putAddressFromTransactionInAIntoB(MachineState state) {
		TransactionData transactionData = this.getTransactionFromA(state);

		// We actually use public key as it has more potential utility (e.g. message verification) than an address
		byte[] bytes = transactionData.getCreatorPublicKey();

		this.setB(state, bytes);
	}

	@Override
	public void putCreatorAddressIntoB(MachineState state) {
		// We actually use public key as it has more potential utility (e.g. message verification) than an address
		byte[] bytes = atData.getCreatorPublicKey();

		this.setB(state, bytes);
	}

	@Override
	public long getCurrentBalance(MachineState state) {
		try {
			Account atAccount = this.getATAccount();

			return atAccount.getConfirmedBalance(Asset.QORT).unscaledValue().longValue();
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch AT's current balance?", e);
		}
	}

	@Override
	public void payAmountToB(long unscaledAmount, MachineState state) {
		Account recipient = getAccountFromB(state);

		long timestamp = this.getNextTransactionTimestamp();
		byte[] reference = this.getLastReference();
		BigDecimal amount = BigDecimal.valueOf(unscaledAmount, 8);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, reference, GenesisAccount.PUBLIC_KEY, BigDecimal.ZERO, null);
		ATTransactionData atTransactionData = new ATTransactionData(baseTransactionData, this.atData.getATAddress(),
				recipient.getAddress(), amount, this.atData.getAssetId(), new byte[0]);
		AtTransaction atTransaction = new AtTransaction(this.repository, atTransactionData);

		// Add to our transactions
		this.transactions.add(atTransaction);
	}

	@Override
	public void messageAToB(MachineState state) {
		byte[] message = state.getA();
		Account recipient = getAccountFromB(state);

		long timestamp = this.getNextTransactionTimestamp();
		byte[] reference = this.getLastReference();

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, reference, GenesisAccount.PUBLIC_KEY, BigDecimal.ZERO, null);
		ATTransactionData atTransactionData = new ATTransactionData(baseTransactionData, this.atData.getATAddress(),
				recipient.getAddress(), BigDecimal.ZERO, this.atData.getAssetId(), message);
		AtTransaction atTransaction = new AtTransaction(this.repository, atTransactionData);

		// Add to our transactions
		this.transactions.add(atTransaction);
	}

	@Override
	public long addMinutesToTimestamp(Timestamp timestamp, long minutes, MachineState state) {
		int blockHeight = timestamp.blockHeight;

		// At least one block in the future
		blockHeight += Math.max(minutes / this.ciyamAtSettings.minutesPerBlock, 1);

		return new Timestamp(blockHeight, 0).longValue();
	}

	@Override
	public void onFinished(long finalBalance, MachineState state) {
		// Refund remaining balance (if any) to AT's creator
		Account creator = this.getCreator();
		long timestamp = this.getNextTransactionTimestamp();
		byte[] reference = this.getLastReference();
		BigDecimal amount = BigDecimal.valueOf(finalBalance, 8);

		BaseTransactionData baseTransactionData = new BaseTransactionData(timestamp, Group.NO_GROUP, reference, GenesisAccount.PUBLIC_KEY, BigDecimal.ZERO, null);
		ATTransactionData atTransactionData = new ATTransactionData(baseTransactionData, this.atData.getATAddress(),
				creator.getAddress(), amount, this.atData.getAssetId(), new byte[0]);
		AtTransaction atTransaction = new AtTransaction(this.repository, atTransactionData);

		// Add to our transactions
		this.transactions.add(atTransaction);
	}

	@Override
	public void onFatalError(MachineState state, ExecutionException e) {
		state.getLogger().error("AT " + this.atData.getATAddress() + " suffered fatal error: " + e.getMessage());
	}

	@Override
	public void platformSpecificPreExecuteCheck(int paramCount, boolean returnValueExpected, MachineState state, short rawFunctionCode)
			throws IllegalFunctionCodeException {
		QortalFunctionCode qortalFunctionCode = QortalFunctionCode.valueOf(rawFunctionCode);

		if (qortalFunctionCode == null)
			throw new IllegalFunctionCodeException("Unknown Qortal function code 0x" + String.format("%04x", rawFunctionCode) + " encountered");

		qortalFunctionCode.preExecuteCheck(2, true, state, rawFunctionCode);
	}

	@Override
	public void platformSpecificPostCheckExecute(FunctionData functionData, MachineState state, short rawFunctionCode) throws ExecutionException {
		QortalFunctionCode qortalFunctionCode = QortalFunctionCode.valueOf(rawFunctionCode);

		qortalFunctionCode.execute(functionData, state, rawFunctionCode);
	}

	// Utility methods

	/** Convert part of little-endian byte[] to long */
	/* package */ static long fromBytes(byte[] bytes, int start) {
		return (bytes[start] & 0xffL) | (bytes[start + 1] & 0xffL) << 8 | (bytes[start + 2] & 0xffL) << 16 | (bytes[start + 3] & 0xffL) << 24
				| (bytes[start + 4] & 0xffL) << 32 | (bytes[start + 5] & 0xffL) << 40 | (bytes[start + 6] & 0xffL) << 48 | (bytes[start + 7] & 0xffL) << 56;
	}

	/** Returns SHA2-192 digest of input - used to verify transaction signatures */
	public static byte[] sha192(byte[] input) {
		try {
			// SHA2-192
			MessageDigest sha192 = MessageDigest.getInstance("SHA-192");
			return sha192.digest(input);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-192 not available");
		}
	}

	/** Verify transaction's SHA2-192 hashed signature matches A2 thru A4 */
	private static void verifyTransaction(TransactionData transactionData, MachineState state) {
		// Compare SHA2-192 of transaction's signature against A2 thru A4
		byte[] hash = sha192(transactionData.getSignature());

		if (state.getA2() != fromBytes(hash, 0) || state.getA3() != fromBytes(hash, 8) || state.getA4() != fromBytes(hash, 16))
			throw new IllegalStateException("Transaction signature in A no longer matches signature from repository");
	}

	/** Returns transaction data from repository using block height & sequence from A1, checking the transaction signatures match too */
	/* package */ TransactionData getTransactionFromA(MachineState state) {
		Timestamp timestamp = new Timestamp(state.getA1());

		try {
			TransactionData transactionData = this.repository.getTransactionRepository().fromHeightAndSequence(timestamp.blockHeight,
					timestamp.transactionSequence);

			if (transactionData == null)
				throw new RuntimeException("AT API unable to fetch transaction?");

			// Check transaction referenced still matches the one from the repository
			verifyTransaction(transactionData, state);

			return transactionData;
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch transaction type?", e);
		}
	}

	/** Returns AT's account */
	/* package */ Account getATAccount() {
		return new Account(this.repository, this.atData.getATAddress());
	}

	/** Returns AT's creator's account */
	private PublicKeyAccount getCreator() {
		return new PublicKeyAccount(this.repository, this.atData.getCreatorPublicKey());
	}

	/** Returns the timestamp to use for next AT Transaction */
	private long getNextTransactionTimestamp() {
		/*
		 * Timestamp is block's timestamp + position in AT-Transactions list.
		 * 
		 * We need increasing timestamps to preserve transaction order and hence a correct signature-reference chain when the block is processed.
		 */

		// XXX THE ABOVE IS NO LONGER TRUE IN QORTAL!
		// return this.blockTimestamp + this.transactions.size();
		throw new RuntimeException("AT timestamp code not fixed!");
	}

	/** Returns AT account's lastReference, taking newly generated ATTransactions into account */
	private byte[] getLastReference() {
		// Use signature from last AT Transaction we generated
		if (!this.transactions.isEmpty())
			return this.transactions.get(this.transactions.size() - 1).getTransactionData().getSignature();

		try {
			// No transactions yet, so look up AT's account's last reference from repository
			Account atAccount = this.getATAccount();

			return atAccount.getLastReference();
		} catch (DataException e) {
			throw new RuntimeException("AT API unable to fetch AT's last reference from repository?", e);
		}
	}

	/**
	 * Returns Account (possibly PublicKeyAccount) based on value in B.
	 * <p>
	 * If bytes in B start with 'Q' then use B as an address, but only if valid.
	 * <p>
	 * Otherwise, assume B is a public key.
	 * @return
	 */
	private Account getAccountFromB(MachineState state) {
		byte[] bBytes = state.getB();

		if (bBytes[0] == 'Q') {
			int zeroIndex = Bytes.indexOf(bBytes, (byte) 0);
			if (zeroIndex > 0) {
				String address = new String(bBytes, 0, zeroIndex);
				if (Crypto.isValidAddress(address))
					return new Account(this.repository, address);
			}
		}

		return new PublicKeyAccount(this.repository, bBytes);
	}

}
