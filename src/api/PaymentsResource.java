package api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import qora.transaction.Transaction;
import qora.transaction.Transaction.ValidationResult;
import repository.DataException;
import repository.Repository;
import repository.RepositoryManager;
import transform.TransformationException;
import transform.transaction.PaymentTransactionTransformer;
import utils.Base58;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;

import data.transaction.PaymentTransactionData;

@Path("/payments")
@Produces({
	MediaType.TEXT_PLAIN
})
@Tag(
	name = "Payments"
)
public class PaymentsResource {

	@Context
	HttpServletRequest request;

	@POST
	@Path("/pay")
	@Operation(
		summary = "Build raw, unsigned payment transaction",
		requestBody = @RequestBody(
			required = true,
			content = @Content(
				mediaType = MediaType.APPLICATION_JSON,
				schema = @Schema(
					implementation = PaymentTransactionData.class
				)
			)
		),
		responses = {
			@ApiResponse(
				description = "raw, unsigned payment transaction encoded in Base58",
				content = @Content(
					mediaType = MediaType.TEXT_PLAIN,
					schema = @Schema(
						type = "string"
					)
				)
			)
		}
	)
	public String buildTransaction(PaymentTransactionData transactionData) {
		try (final Repository repository = RepositoryManager.getRepository()) {
			Transaction transaction = Transaction.fromData(repository, transactionData);

			ValidationResult result = transaction.isValid();
			if (result != ValidationResult.OK)
				throw new ApiException(400, ApiError.INVALID_DATA.getCode(), "Transaction invalid: " + result.name());

			byte[] bytes = PaymentTransactionTransformer.toBytes(transactionData);
			return Base58.encode(bytes);
		} catch (TransformationException e) {
			throw ApiErrorFactory.getInstance().createError(ApiError.UNKNOWN, e);
		} catch (DataException e) {
			throw ApiErrorFactory.getInstance().createError(ApiError.REPOSITORY_ISSUE, e);
		}
	}

}