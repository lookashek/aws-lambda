package dev.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.lambda.model.BedrockRequestBody;
import dev.lambda.model.LambdaRequest;
import dev.lambda.model.LambdaResponse;
import dev.lambda.model.kendra.CustomDocumentAttribute;
import dev.lambda.model.kendra.CustomDocumentAttributeValue;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@SpringBootApplication
public class Application {

  private final static String PROMPT_HEAD = "\n\nHuman:";
  private final static String PROMPT_TAIL = "\n\nAssistant:";
  private final static String PROMPT_SUMMARY = "Please generate a concise summary of the provided corporate document, highlighting the key points and relevant details that can assist in keyword search optimization. Focus on capturing the essence of the content, including objectives, main findings, significant figures, and any conclusions or recommendations that are crucial for understanding the document's purpose and content. \n### vDocument:\n";
  private final static String PROMPT_CATEGORY = "Analyze the provided corporate document and determine the most fitting category from the predefined set. Return only the category name that best represents the document's content. If the document does not align with the provided categories, categorize it as 'OTHER'. Do not include any additional information or summary, just the category name. \n ### predefined set: \n";
  private final static String PROMPT_KEYWORDS = "Identify and extract the keywords from the provided corporate document, which describes various business processes. List the keywords separated by commas, without any additional explanations or context. Ensure that the keywords are relevant to the document's processes and content, enabling efficient search and categorization.";
  @Value("${bedrock.model-id}")
  String modelId;

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  public Function<LambdaRequest, LambdaResponse> postHook(PostHookService postHookService) {
    return (s) -> postHookService.process(s.s3Bucket(), s.s3ObjectKey());
  }

  private CustomDocumentAttribute createAttribute(String name, String value) {
    CustomDocumentAttributeValue val = new CustomDocumentAttributeValue(value);
    return new CustomDocumentAttribute(name, val);
  }

  @Component
  public class PostHookService {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LambdaResponse process(String bucketName, String keyName) {

      S3Client s3 = S3Client.builder().build();

      System.out.printf("Bucket name: %s, keyName: %s%n", bucketName, keyName);

      GetObjectRequest objectRequest = GetObjectRequest
              .builder()
              .key(keyName)
              .bucket(bucketName)
              .build();

      System.out.printf("🤖 Model ID: %s%n", modelId);


      try {
        ResponseInputStream<GetObjectResponse> objectBytes = s3.getObject(objectRequest);

        String kendraDocumentString = new String(objectBytes.readAllBytes(), StandardCharsets.UTF_8);
        System.out.printf("Kendra document raw: %s%n", kendraDocumentString);

        Map<String, Object> kendraDocument = objectMapper.readValue(kendraDocumentString, HashMap.class);
        Map<String, Object> textContent = (Map<String, Object>) kendraDocument.get("textContent");
        String documentText = (String) textContent.get("documentBodyText");
        System.out.printf("Extracted data from document: %s%n", documentText);

        var category = getEnrichmentFromAI(documentText, PROMPT_CATEGORY);
        var summary = getEnrichmentFromAI(documentText, PROMPT_SUMMARY);
        var keywords = getEnrichmentFromAI(documentText, PROMPT_KEYWORDS);


        LambdaResponse response = new LambdaResponse("1.0", "updated_document",
                List.of(createAttribute("_category", category),
                        createAttribute("summary", summary),
                        createAttribute("keywords", keywords)));

        System.out.printf("Response : %s%n", response);


        return response;


      } catch (Exception e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }

    }

    private String getEnrichmentFromAI(String documentText, String prompt) {
      try (BedrockRuntimeClient bedrockClient = BedrockRuntimeClient.builder()
              .region(Region.EU_CENTRAL_1)
              .build()) {

        String bedrockBody = BedrockRequestBody.builder()
                .withModelId(modelId)
                .withPrompt(PROMPT_HEAD + prompt + documentText + PROMPT_TAIL)
                .withInferenceParameter("temperature", 0.40)
                .withInferenceParameter("p", 0.75)
                .withInferenceParameter("k", 0)
                .withInferenceParameter("max_tokens", 200)
                .build();

        InvokeModelRequest invokeModelRequest = InvokeModelRequest.builder()
                .modelId(modelId)
                .body(SdkBytes.fromString(bedrockBody, Charset.defaultCharset()))
                .build();

        InvokeModelResponse invokeModelResponse = bedrockClient.invokeModel(invokeModelRequest);
        JSONObject responseAsJson = new JSONObject(invokeModelResponse.body().asUtf8String());

        System.out.printf("🤖 Response from Berdrock RAW : %s%n", responseAsJson.toString(2));

        var responseText = responseAsJson
                .getString("completion");

        System.out.printf("🤖 Response: %s%n", responseText);

        return responseText;

      } catch (Exception e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
    }

  }
}

