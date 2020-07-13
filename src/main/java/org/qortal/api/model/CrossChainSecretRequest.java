package org.qortal.api.model;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;

import io.swagger.v3.oas.annotations.media.Schema;

@XmlAccessorType(XmlAccessType.FIELD)
public class CrossChainSecretRequest {

	@Schema(description = "Public key to match AT's 'recipient'", example = "C6wuddsBV3HzRrXUtezE7P5MoRXp5m3mEDokRDGZB6ry")
	public byte[] recipientPublicKey;

	@Schema(description = "Qortal AT address")
	public String atAddress;

	@Schema(description = "secret-A (32 bytes)", example = "FHMzten4he9jZ4HGb4297Utj6F5g2w7serjq2EnAg2s1")
	public byte[] secretA;

	@Schema(description = "secret-B (32 bytes)", example = "EN2Bgx3BcEMtxFCewmCVSMkfZjVKYhx3KEXC5A21KBGx")
	public byte[] secretB;

	public CrossChainSecretRequest() {
	}

}
