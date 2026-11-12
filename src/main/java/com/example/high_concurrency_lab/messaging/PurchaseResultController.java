package com.example.high_concurrency_lab.messaging;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/** Reads completed MySQL results only; never publishes messages, retries a purchase, or deducts stock. */
@RestController
@RequiredArgsConstructor
public class PurchaseResultController {

    private final PurchaseResultRepository resultRepository;

    /**
     * Returns a completed outcome, including business rejections, as HTTP 200.
     * @param requestId the original request UUID parsed from the URL by Spring
     * @return the stored result's public fields
     * @throws ResponseStatusException with HTTP 404 when no completed result is stored;
     *         absence does not prove that the request is pending, rejected, or was ever submitted
     */
    @GetMapping("/api/purchase-results/{requestId}")
    public PurchaseResultResponse getResult(@PathVariable("requestId") UUID requestId) {
        // The callback creates an error only when the query is empty; it does not start processing.
        PurchaseResult result = resultRepository.findById(requestId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No completed result is available for this request ID. It may be unprocessed or unknown."));
        return PurchaseResultResponse.from(result);
    }

    /** Describes an unavailable completed result without incorrectly declaring purchase failure or pending status. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleUnavailableResult(ResponseStatusException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
        problem.setTitle("Purchase result not available");
        problem.setProperty("code", "PURCHASE_RESULT_NOT_AVAILABLE");
        return ResponseEntity.status(exception.getStatusCode()).body(problem);
    }
}
