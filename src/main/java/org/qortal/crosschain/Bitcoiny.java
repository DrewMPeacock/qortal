package org.qortal.crosschain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.AddressFormatException;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.UTXO;
import org.bitcoinj.core.UTXOProvider;
import org.bitcoinj.core.UTXOProviderException;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicHierarchy;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.script.Script.ScriptType;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.Wallet;
import org.qortal.crypto.Crypto;
import org.qortal.utils.Amounts;
import org.qortal.utils.BitTwiddling;

import com.google.common.hash.HashCode;

/** Bitcoin-like (Bitcoin, Litecoin, etc.) support */
public abstract class Bitcoiny implements ForeignBlockchain {

	protected static final Logger LOGGER = LogManager.getLogger(Bitcoiny.class);

	public static final int HASH160_LENGTH = 20;

	protected final BitcoinyBlockchainProvider blockchain;
	protected final Context bitcoinjContext;
	protected final String currencyCode;

	protected final NetworkParameters params;

	/** Keys that have been previously partially, or fully, spent */
	protected final Set<ECKey> spentKeys = new HashSet<>();

	/** Byte offset into raw block headers to block timestamp. */
	private static final int TIMESTAMP_OFFSET = 4 + 32 + 32;

	// Constructors and instance

	protected Bitcoiny(BitcoinyBlockchainProvider blockchain, Context bitcoinjContext, String currencyCode) {
		this.blockchain = blockchain;
		this.bitcoinjContext = bitcoinjContext;
		this.currencyCode = currencyCode;

		this.params = this.bitcoinjContext.getParams();
	}

	// Getters & setters

	public BitcoinyBlockchainProvider getBlockchainProvider() {
		return this.blockchain;
	}

	public Context getBitcoinjContext() {
		return this.bitcoinjContext;
	}

	public String getCurrencyCode() {
		return this.currencyCode;
	}

	public NetworkParameters getNetworkParameters() {
		return this.params;
	}

	// Interface obligations 

	@Override
	public boolean isValidAddress(String address) {
		try {
			ScriptType addressType = Address.fromString(this.params, address).getOutputScriptType();

			return addressType == ScriptType.P2PKH || addressType == ScriptType.P2SH;
		} catch (AddressFormatException e) {
			return false;
		}
	}

	@Override
	public boolean isValidWalletKey(String walletKey) {
		return this.isValidXprv(walletKey);
	}

	// Actual useful methods for use by other classes

	public String format(Coin amount) {
		return this.format(amount.value);
	}

	public String format(long amount) {
		return Amounts.prettyAmount(amount) + " " + this.currencyCode;
	}

	public boolean isValidXprv(String xprv58) {
		try {
			Context.propagate(this.bitcoinjContext);
			DeterministicKey.deserializeB58(null, xprv58, this.params);
			return true;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** Returns P2PKH address using passed public key hash. */
	public String pkhToAddress(byte[] publicKeyHash) {
		Context.propagate(this.bitcoinjContext);
		return LegacyAddress.fromPubKeyHash(this.params, publicKeyHash).toString();
	}

	/** Returns P2SH address using passed redeem script. */
	public String deriveP2shAddress(byte[] redeemScriptBytes) {
		Context.propagate(bitcoinjContext);
		byte[] redeemScriptHash = Crypto.hash160(redeemScriptBytes);
		return LegacyAddress.fromScriptHash(this.params, redeemScriptHash).toString();
	}

	/**
	 * Returns median timestamp from latest 11 blocks, in seconds.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public int getMedianBlockTime() throws ForeignBlockchainException {
		int height = this.blockchain.getCurrentHeight();

		// Grab latest 11 blocks
		List<byte[]> blockHeaders = this.blockchain.getRawBlockHeaders(height - 11, 11);
		if (blockHeaders.size() < 11)
			throw new ForeignBlockchainException("Not enough blocks to determine median block time");

		List<Integer> blockTimestamps = blockHeaders.stream().map(blockHeader -> BitTwiddling.intFromLEBytes(blockHeader, TIMESTAMP_OFFSET)).collect(Collectors.toList());

		// Descending order
		blockTimestamps.sort((a, b) -> Integer.compare(b, a));

		// Pick median
		return blockTimestamps.get(5);
	}

	/** Returns fee per transaction KB. To be overridden for testnet/regtest. */
	public Coin getFeePerKb() {
		return this.bitcoinjContext.getFeePerKb();
	}

	/**
	 * Returns fixed P2SH spending fee, in sats per 1000bytes, optionally for historic timestamp.
	 * 
	 * @param timestamp optional milliseconds since epoch, or null for 'now'
	 * @return sats per 1000bytes
	 * @throws ForeignBlockchainException if something went wrong
	 */
	public abstract long getP2shFee(Long timestamp) throws ForeignBlockchainException;

	/**
	 * Returns confirmed balance, based on passed payment script.
	 * <p>
	 * @return confirmed balance, or zero if script unknown
	 * @throws ForeignBlockchainException if there was an error
	 */
	public long getConfirmedBalance(String base58Address) throws ForeignBlockchainException {
		return this.blockchain.getConfirmedBalance(addressToScriptPubKey(base58Address));
	}

	/**
	 * Returns list of unspent outputs pertaining to passed address.
	 * <p>
	 * @return list of unspent outputs, or empty list if address unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	public List<TransactionOutput> getUnspentOutputs(String base58Address) throws ForeignBlockchainException {
		List<UnspentOutput> unspentOutputs = this.blockchain.getUnspentOutputs(addressToScriptPubKey(base58Address), false);

		List<TransactionOutput> unspentTransactionOutputs = new ArrayList<>();
		for (UnspentOutput unspentOutput : unspentOutputs) {
			List<TransactionOutput> transactionOutputs = this.getOutputs(unspentOutput.hash);

			unspentTransactionOutputs.add(transactionOutputs.get(unspentOutput.index));
		}

		return unspentTransactionOutputs;
	}

	/**
	 * Returns list of outputs pertaining to passed transaction hash.
	 * <p>
	 * @return list of outputs, or empty list if transaction unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	public List<TransactionOutput> getOutputs(byte[] txHash) throws ForeignBlockchainException {
		byte[] rawTransactionBytes = this.blockchain.getRawTransaction(txHash);

		// XXX bitcoinj: replace with getTransaction() below
		Context.propagate(bitcoinjContext);
		Transaction transaction = new Transaction(this.params, rawTransactionBytes);
		return transaction.getOutputs();
	}

	/**
	 * Returns list of transaction hashes pertaining to passed address.
	 * <p>
	 * @return list of unspent outputs, or empty list if script unknown
	 * @throws ForeignBlockchainException if there was an error.
	 */
	public List<TransactionHash> getAddressTransactions(String base58Address, boolean includeUnconfirmed) throws ForeignBlockchainException {
		return this.blockchain.getAddressTransactions(addressToScriptPubKey(base58Address), includeUnconfirmed);
	}

	/**
	 * Returns list of raw, confirmed transactions involving given address.
	 * <p>
	 * @throws ForeignBlockchainException if there was an error
	 */
	public List<byte[]> getAddressTransactions(String base58Address) throws ForeignBlockchainException {
		List<TransactionHash> transactionHashes = this.blockchain.getAddressTransactions(addressToScriptPubKey(base58Address), false);

		List<byte[]> rawTransactions = new ArrayList<>();
		for (TransactionHash transactionInfo : transactionHashes) {
			byte[] rawTransaction = this.blockchain.getRawTransaction(HashCode.fromString(transactionInfo.txHash).asBytes());
			rawTransactions.add(rawTransaction);
		}

		return rawTransactions;
	}

	/**
	 * Returns transaction info for passed transaction hash.
	 * <p>
	 * @throws ForeignBlockchainException.NotFoundException if transaction unknown
	 * @throws ForeignBlockchainException if error occurs
	 */
	public BitcoinyTransaction getTransaction(String txHash) throws ForeignBlockchainException {
		return this.blockchain.getTransaction(txHash);
	}

	/**
	 * Broadcasts raw transaction to network.
	 * <p>
	 * @throws ForeignBlockchainException if error occurs
	 */
	public void broadcastTransaction(Transaction transaction) throws ForeignBlockchainException {
		this.blockchain.broadcastTransaction(transaction.bitcoinSerialize());
	}

	/**
	 * Returns bitcoinj transaction sending <tt>amount</tt> to <tt>recipient</tt>.
	 * 
	 * @param xprv58 BIP32 private key
	 * @param recipient P2PKH address
	 * @param amount unscaled amount
	 * @param feePerByte unscaled fee per byte, or null to use default fees
	 * @return transaction, or null if insufficient funds
	 */
	public Transaction buildSpend(String xprv58, String recipient, long amount, Long feePerByte) {
		Context.propagate(bitcoinjContext);

		Wallet wallet = Wallet.fromSpendingKeyB58(this.params, xprv58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
		wallet.setUTXOProvider(new WalletAwareUTXOProvider(this, wallet));

		Address destination = Address.fromString(this.params, recipient);
		SendRequest sendRequest = SendRequest.to(destination, Coin.valueOf(amount));

		if (feePerByte != null)
			sendRequest.feePerKb = Coin.valueOf(feePerByte * 1000L); // Note: 1000 not 1024
		else
			// Allow override of default for TestNet3, etc.
			sendRequest.feePerKb = this.getFeePerKb();

		try {
			wallet.completeTx(sendRequest);
			return sendRequest.tx;
		} catch (InsufficientMoneyException e) {
			return null;
		}
	}

	/**
	 * Returns bitcoinj transaction sending <tt>amount</tt> to <tt>recipient</tt> using default fees.
	 * 
	 * @param xprv58 BIP32 private key
	 * @param recipient P2PKH address
	 * @param amount unscaled amount
	 * @return transaction, or null if insufficient funds
	 */
	public Transaction buildSpend(String xprv58, String recipient, long amount) {
		return buildSpend(xprv58, recipient, amount, null);
	}

	/**
	 * Returns unspent Bitcoin balance given 'm' BIP32 key.
	 *
	 * @param xprv58 BIP32 extended Bitcoin private key
	 * @return unspent BTC balance, or null if unable to determine balance
	 */
	public Long getWalletBalance(String xprv58) {
		Context.propagate(bitcoinjContext);

		Wallet wallet = Wallet.fromSpendingKeyB58(this.params, xprv58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
		wallet.setUTXOProvider(new WalletAwareUTXOProvider(this, wallet));

		Coin balance = wallet.getBalance();
		if (balance == null)
			return null;

		return balance.value;
	}

	/**
	 * Returns first unused receive address given 'm' BIP32 key.
	 *
	 * @param xprv58 BIP32 extended Bitcoin private key
	 * @return Bitcoin P2PKH address
	 * @throws ForeignBlockchainException if something went wrong
	 */
	public String getUnusedReceiveAddress(String xprv58) throws ForeignBlockchainException {
		Context.propagate(bitcoinjContext);

		Wallet wallet = Wallet.fromSpendingKeyB58(this.params, xprv58, DeterministicHierarchy.BIP32_STANDARDISATION_TIME_SECS);
		DeterministicKeyChain keyChain = wallet.getActiveKeyChain();

		keyChain.setLookaheadSize(WalletAwareUTXOProvider.LOOKAHEAD_INCREMENT);
		keyChain.maybeLookAhead();

		final int keyChainPathSize = keyChain.getAccountPath().size();
		List<DeterministicKey> keys = new ArrayList<>(keyChain.getLeafKeys());

		int ki = 0;
		do {
			for (; ki < keys.size(); ++ki) {
				DeterministicKey dKey = keys.get(ki);
				List<ChildNumber> dKeyPath = dKey.getPath();

				// If keyChain is based on 'm', then make sure dKey is m/0/ki
				if (dKeyPath.size() != keyChainPathSize + 2 || dKeyPath.get(dKeyPath.size() - 2) != ChildNumber.ZERO)
					continue;

				// Check unspent
				Address address = Address.fromKey(this.params, dKey, ScriptType.P2PKH);
				byte[] script = ScriptBuilder.createOutputScript(address).getProgram();

				List<UnspentOutput> unspentOutputs = this.blockchain.getUnspentOutputs(script, false);

				/*
				 * If there are no unspent outputs then either:
				 * a) all the outputs have been spent
				 * b) address has never been used
				 * 
				 * For case (a) we want to remember not to check this address (key) again.
				 */

				if (unspentOutputs.isEmpty()) {
					// If this is a known key that has been spent before, then we can skip asking for transaction history
					if (this.spentKeys.contains(dKey)) {
						wallet.getActiveKeyChain().markKeyAsUsed((DeterministicKey) dKey);
						continue;
					}

					// Ask for transaction history - if it's empty then key has never been used
					List<TransactionHash> historicTransactionHashes = this.blockchain.getAddressTransactions(script, false);

					if (!historicTransactionHashes.isEmpty()) {
						// Fully spent key - case (a)
						this.spentKeys.add(dKey);
						wallet.getActiveKeyChain().markKeyAsUsed(dKey);
					} else {
						// Key never been used - case (b)
						return address.toString();
					}
				}

				// Key has unspent outputs, hence used, so no good to us
				this.spentKeys.remove(dKey);
			}

			// Generate some more keys
			keyChain.setLookaheadSize(keyChain.getLookaheadSize() + WalletAwareUTXOProvider.LOOKAHEAD_INCREMENT);
			keyChain.maybeLookAhead();

			// This returns all keys, including those already in 'keys'
			List<DeterministicKey> allLeafKeys = keyChain.getLeafKeys();
			// Add only new keys onto our list of keys to search
			List<DeterministicKey> newKeys = allLeafKeys.subList(ki, allLeafKeys.size());
			keys.addAll(newKeys);
			// Fall-through to checking more keys as now 'ki' is smaller than 'keys.size()' again

			// Process new keys
		} while (true);
	}

	// UTXOProvider support

	static class WalletAwareUTXOProvider implements UTXOProvider {
		private static final int LOOKAHEAD_INCREMENT = 3;

		private final Bitcoiny bitcoiny;
		private final Wallet wallet;

		private final DeterministicKeyChain keyChain;

		public WalletAwareUTXOProvider(Bitcoiny bitcoiny, Wallet wallet) {
			this.bitcoiny = bitcoiny;
			this.wallet = wallet;
			this.keyChain = this.wallet.getActiveKeyChain();

			// Set up wallet's key chain
			this.keyChain.setLookaheadSize(LOOKAHEAD_INCREMENT);
			this.keyChain.maybeLookAhead();
		}

		@Override
		public List<UTXO> getOpenTransactionOutputs(List<ECKey> keys) throws UTXOProviderException {
			List<UTXO> allUnspentOutputs = new ArrayList<>();
			final boolean coinbase = false;

			int ki = 0;
			do {
				boolean areAllKeysUnspent = true;

				for (; ki < keys.size(); ++ki) {
					ECKey key = keys.get(ki);

					Address address = Address.fromKey(this.bitcoiny.params, key, ScriptType.P2PKH);
					byte[] script = ScriptBuilder.createOutputScript(address).getProgram();

					List<UnspentOutput> unspentOutputs;
					try {
						unspentOutputs = this.bitcoiny.blockchain.getUnspentOutputs(script, false);
					} catch (ForeignBlockchainException e) {
						throw new UTXOProviderException(String.format("Unable to fetch unspent outputs for %s", address));
					}

					/*
					 * If there are no unspent outputs then either:
					 * a) all the outputs have been spent
					 * b) address has never been used
					 * 
					 * For case (a) we want to remember not to check this address (key) again.
					 */

					if (unspentOutputs.isEmpty()) {
						// If this is a known key that has been spent before, then we can skip asking for transaction history
						if (this.bitcoiny.spentKeys.contains(key)) {
							this.wallet.getActiveKeyChain().markKeyAsUsed((DeterministicKey) key);
							areAllKeysUnspent = false;
							continue;
						}

						// Ask for transaction history - if it's empty then key has never been used
						List<TransactionHash> historicTransactionHashes;
						try {
							historicTransactionHashes = this.bitcoiny.blockchain.getAddressTransactions(script, false);
						} catch (ForeignBlockchainException e) {
							throw new UTXOProviderException(String.format("Unable to fetch transaction history for %s", address));
						}

						if (!historicTransactionHashes.isEmpty()) {
							// Fully spent key - case (a)
							this.bitcoiny.spentKeys.add(key);
							this.wallet.getActiveKeyChain().markKeyAsUsed((DeterministicKey) key);
							areAllKeysUnspent = false;
						} else {
							// Key never been used - case (b)
						}

						continue;
					}

					// If we reach here, then there's definitely at least one unspent key
					this.bitcoiny.spentKeys.remove(key);

					for (UnspentOutput unspentOutput : unspentOutputs) {
						List<TransactionOutput> transactionOutputs;
						try {
							transactionOutputs = this.bitcoiny.getOutputs(unspentOutput.hash);
						} catch (ForeignBlockchainException e) {
							throw new UTXOProviderException(String.format("Unable to fetch outputs for TX %s",
									HashCode.fromBytes(unspentOutput.hash)));
						}

						TransactionOutput transactionOutput = transactionOutputs.get(unspentOutput.index);

						UTXO utxo = new UTXO(Sha256Hash.wrap(unspentOutput.hash), unspentOutput.index,
								Coin.valueOf(unspentOutput.value), unspentOutput.height, coinbase,
								transactionOutput.getScriptPubKey());

						allUnspentOutputs.add(utxo);
					}
				}

				if (!areAllKeysUnspent) {
					// Generate some more keys
					this.keyChain.setLookaheadSize(this.keyChain.getLookaheadSize() + LOOKAHEAD_INCREMENT);
					this.keyChain.maybeLookAhead();

					// This returns all keys, including those already in 'keys'
					List<DeterministicKey> allLeafKeys = this.keyChain.getLeafKeys();
					// Add only new keys onto our list of keys to search
					List<DeterministicKey> newKeys = allLeafKeys.subList(ki, allLeafKeys.size());
					keys.addAll(newKeys);
					// Fall-through to checking more keys as now 'ki' is smaller than 'keys.size()' again
				}

				// If we have processed all keys, then we're done
			} while (ki < keys.size());

			return allUnspentOutputs;
		}

		@Override
		public int getChainHeadHeight() throws UTXOProviderException {
			try {
				return this.bitcoiny.blockchain.getCurrentHeight();
			} catch (ForeignBlockchainException e) {
				throw new UTXOProviderException("Unable to determine Bitcoiny chain height");
			}
		}

		@Override
		public NetworkParameters getParams() {
			return this.bitcoiny.params;
		}
	}

	// Utility methods for us

	protected byte[] addressToScriptPubKey(String base58Address) {
		Context.propagate(bitcoinjContext);
		Address address = Address.fromString(this.params, base58Address);
		return ScriptBuilder.createOutputScript(address).getProgram();
	}

}