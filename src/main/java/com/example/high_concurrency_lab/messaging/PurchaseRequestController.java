package com.example.high_concurrency_lab.messaging;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Exposes broker-acknowledged submission; purchase completion is asynchronous and is not an HTTP receipt. */
@RestController
@RequiredArgsConstructor
public class PurchaseRequestController {

    private final PurchaseRequestPublisher publisher;

    /** Validates JSON input and returns 202 only after Kafka acknowledges the message; never deducts stock. */
    @PostMapping("/api/purchase-requests")
    public ResponseEntity<SubmissionReceipt> submit(@Valid @RequestBody PurchaseRequest request) {
        publisher.publish(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new SubmissionReceipt(
                request.requestId(), "SUBMITTED", "Kafka acknowledged the request. This receipt does not confirm purchase success."));
    }

    /** Converts an unconfirmed send into an explicit error without claiming that the intent was rejected. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ProblemDetail> handleSubmissionFailure(ResponseStatusException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
        problem.setTitle("Purchase submission not confirmed");
        problem.setProperty("code", "PURCHASE_SUBMISSION_UNCONFIRMED");
        return ResponseEntity.status(exception.getStatusCode()).body(problem);
    }

    /** A broker-acknowledged submission receipt, not an order, stock reservation, or persisted business result. */
    public record SubmissionReceipt(UUID requestId, String state, String message) {
    }
}
