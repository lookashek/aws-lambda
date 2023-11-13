package dev.lambda.model;

import dev.lambda.model.kendra.CustomDocumentAttribute;

import java.util.List;

public record LambdaResponse(String version, String s3ObjectKey,
                             List<CustomDocumentAttribute> metadataUpdates
) {
}
