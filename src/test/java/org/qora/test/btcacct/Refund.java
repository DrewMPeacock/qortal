package org.qora.test.btcacct;

import java.security.Security;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.LegacyAddress;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script.ScriptType;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.qora.controller.Controller;
import org.qora.crosschain.BTC;
import org.qora.crosschain.BTCACCT;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryFactory;
import org.qora.repository.RepositoryManager;
import org.qora.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qora.settings.Settings;

import com.google.common.hash.HashCode;

/**
 * Initiator must be Qora-chain so that initiator can send initial message to BTC P2SH then Qora can scan for P2SH add send corresponding message to Qora AT.
 *
 * Initiator (wants Qora, has BTC)
 * 		Funds BTC P2SH address
 * 
 * Responder (has Qora, wants BTC)
 * 		Builds Qora ACCT AT and funds it with Qora
 * 
 * Initiator sends recipient+secret+script as input to BTC P2SH address, releasing BTC amount - fees to responder
 * 
 * Qora nodes scan for P2SH output, checks amount and recipient and if ok sends secret to Qora ACCT AT
 * (Or it's possible to feed BTC transaction details into Qora AT so it can check them itself?)
 * 
 * Qora ACCT AT sends its Qora to initiator
 *
 */

public class Refund {

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
	}

	private static void usage(String error) {
		if (error != null)
			System.err.println(error);

		System.err.println(String.format("usage: Refund <P2SH-address> <refund-BTC-PRIVATE-KEY> <redeem-BTC-P2PKH> <HASH160-of-secret> <locktime> (<BTC-redeem/refund-fee>)"));
		System.err.println(String.format("example: Refund "
				+ "2NEZboTLhBDPPQciR7sExBhy3TsDi7wV3Cv \\\n"
				+ "\tef027fb5828c5e201eaf6de4cd3b0b340d16a191ef848cd691f35ef8f727358c9c01b576fb7e \\\n"
				+ "\tn2N5VKrzq39nmuefZwp3wBiF4icdXX2B6o \\\n"
				+ "\td1b64100879ad93ceaa3c15929b6fe8550f54967 \\\n"
				+ "\t1585920000"));
		System.exit(1);
	}

	public static void main(String[] args) {
		if (args.length < 5 || args.length > 6)
			usage(null);

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Settings.fileInstance("settings-test.json");

		BTC btc = BTC.getInstance();
		NetworkParameters params = btc.getNetworkParameters();

		Address p2shAddress = null;
		byte[] refundPrivateKey = null;
		Address redeemBitcoinAddress = null;
		byte[] secretHash = null;
		int lockTime = 0;
		Coin bitcoinFee = BTCACCT.DEFAULT_BTC_FEE;

		int argIndex = 0;
		try {
			p2shAddress = Address.fromString(params, args[argIndex++]);
			if (p2shAddress.getOutputScriptType() != ScriptType.P2SH)
				usage("P2SH address invalid");

			refundPrivateKey = HashCode.fromString(args[argIndex++]).asBytes();
			// Auto-trim
			if (refundPrivateKey.length >= 37 && refundPrivateKey.length <= 38)
				refundPrivateKey = Arrays.copyOfRange(refundPrivateKey, 1, 33);
			if (refundPrivateKey.length != 32)
				usage("Refund private key must be 32 bytes");

			redeemBitcoinAddress = Address.fromString(params, args[argIndex++]);
			if (redeemBitcoinAddress.getOutputScriptType() != ScriptType.P2PKH)
				usage("Their BTC address must be in P2PKH form");

			secretHash = HashCode.fromString(args[argIndex++]).asBytes();
			if (secretHash.length != 20)
				usage("HASH160 of secret must be 20 bytes");

			lockTime = Integer.parseInt(args[argIndex++]);

			if (args.length > argIndex)
				bitcoinFee = Coin.parseCoin(args[argIndex++]);
		} catch (IllegalArgumentException e) {
			usage(String.format("Invalid argument %d: %s", argIndex, e.getMessage()));
		}

		try {
			RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(Controller.getRepositoryUrl());
			RepositoryManager.setRepositoryFactory(repositoryFactory);
		} catch (DataException e) {
			throw new RuntimeException("Repository startup issue: " + e.getMessage());
		}

		try (final Repository repository = RepositoryManager.getRepository()) {
			System.out.println("Confirm the following is correct based on the info you've given:");

			System.out.println(String.format("Refund PRIVATE key: %s", HashCode.fromBytes(refundPrivateKey)));
			System.out.println(String.format("Redeem Bitcoin address: %s", redeemBitcoinAddress));
			System.out.println(String.format("Redeem script lockTime: %s (%d)", LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC), lockTime));
			System.out.println(String.format("P2SH address: %s", p2shAddress));
			System.out.println(String.format("Refund miner's fee: %s", BTC.FORMAT.format(bitcoinFee)));

			// New/derived info

			System.out.println("\nCHECKING info from other party:");

			ECKey refundKey = ECKey.fromPrivate(refundPrivateKey);
			Address refundAddress = Address.fromKey(params, refundKey, ScriptType.P2PKH);
			System.out.println(String.format("Refund recipient (PKH): %s (%s)", refundAddress, HashCode.fromBytes(refundAddress.getHash())));

			byte[] redeemScriptBytes = BTCACCT.buildScript(refundAddress.getHash(), lockTime, redeemBitcoinAddress.getHash(), secretHash);
			System.out.println(String.format("Redeem script: %s", HashCode.fromBytes(redeemScriptBytes)));

			byte[] redeemScriptHash = BTC.hash160(redeemScriptBytes);
			Address derivedP2shAddress = LegacyAddress.fromScriptHash(params, redeemScriptHash);

			if (!derivedP2shAddress.equals(p2shAddress)) {
				System.err.println(String.format("Derived P2SH address %s does not match given address %s", derivedP2shAddress, p2shAddress));
				System.exit(2);
			}

			// Some checks

			System.out.println("\nProcessing:");

			long medianBlockTime = BTC.getInstance().getMedianBlockTime();
			System.out.println(String.format("Median block time: %s", LocalDateTime.ofInstant(Instant.ofEpochSecond(medianBlockTime), ZoneOffset.UTC)));

			long now = System.currentTimeMillis();

			if (now < medianBlockTime * 1000L) {
				System.err.println(String.format("Too soon (%s) to refund based on median block time %s", LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC), LocalDateTime.ofInstant(Instant.ofEpochSecond(medianBlockTime), ZoneOffset.UTC)));
				System.exit(2);
			}

			if (now < lockTime * 1000L) {
				System.err.println(String.format("Too soon (%s) to refund based on lockTime %s", LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC), LocalDateTime.ofInstant(Instant.ofEpochSecond(lockTime), ZoneOffset.UTC)));
				System.exit(2);
			}

			// Check P2SH is funded
			final long startTime = ((int) (System.currentTimeMillis() / 1000L)) - 86400;

			Coin p2shBalance = BTC.getInstance().getBalance(p2shAddress.toString(), startTime);
			if (p2shBalance == null) {
				System.err.println(String.format("Unable to check P2SH address %s balance", p2shAddress));
				System.exit(2);
			}
			System.out.println(String.format("P2SH address %s balance: %s BTC", p2shAddress, p2shBalance.toPlainString()));

			// Grab all P2SH funding transactions (just in case there are more than one)
			List<TransactionOutput> fundingOutputs = BTC.getInstance().getOutputs(p2shAddress.toString(), startTime);
			if (fundingOutputs == null) {
				System.err.println(String.format("Can't find outputs for P2SH"));
				System.exit(2);
			}

			System.out.println(String.format("Found %d output%s for P2SH", fundingOutputs.size(), (fundingOutputs.size() != 1 ? "s" : "")));

			for (TransactionOutput fundingOutput : fundingOutputs)
				System.out.println(String.format("Output %s:%d amount %s", HashCode.fromBytes(fundingOutput.getParentTransactionHash().getBytes()), fundingOutput.getIndex(), BTC.FORMAT.format(fundingOutput.getValue())));

			if (fundingOutputs.isEmpty()) {
				System.err.println(String.format("Can't refund spent/unfunded P2SH"));
				System.exit(2);
			}

			if (fundingOutputs.size() != 1) {
				System.err.println(String.format("Expecting only one unspent output for P2SH"));
				System.exit(2);
			}

			TransactionOutput fundingOutput = fundingOutputs.get(0);
			System.out.println(String.format("Using output %s:%d for refund", HashCode.fromBytes(fundingOutput.getParentTransactionHash().getBytes()), fundingOutput.getIndex()));

			Coin refundAmount = p2shBalance.subtract(bitcoinFee);
			System.out.println(String.format("Spending %s of output, with %s as mining fee", BTC.FORMAT.format(refundAmount), BTC.FORMAT.format(bitcoinFee)));

			Transaction redeemTransaction = BTCACCT.buildRefundTransaction(refundAmount, refundKey, fundingOutput, redeemScriptBytes, lockTime);

			byte[] redeemBytes = redeemTransaction.bitcoinSerialize();

			System.out.println(String.format("\nLoad this transaction into your wallet and broadcast:\n%s\n", HashCode.fromBytes(redeemBytes).toString()));
		} catch (NumberFormatException e) {
			usage(String.format("Number format exception: %s", e.getMessage()));
		} catch (DataException e) {
			throw new RuntimeException("Repository issue: " + e.getMessage());
		}
	}

}
