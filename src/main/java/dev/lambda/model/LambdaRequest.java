package dev.lambda.model;

public record LambdaRequest(String version, String s3Bucket, String s3ObjectKey, Object metadata) {
}
