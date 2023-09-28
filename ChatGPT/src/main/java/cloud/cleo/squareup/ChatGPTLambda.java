/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package cloud.cleo.squareup;

import static cloud.cleo.squareup.LexInputMode.TEXT;
import cloud.cleo.squareup.functions.AbstractFunction;
import cloud.cleo.squareup.json.DurationDeserializer;
import cloud.cleo.squareup.json.DurationSerializer;
import cloud.cleo.squareup.json.LocalDateDeserializer;
import cloud.cleo.squareup.json.LocalDateSerializer;
import cloud.cleo.squareup.json.LocalTimeDeserializer;
import cloud.cleo.squareup.json.LocalTimeSerializer;
import cloud.cleo.squareup.json.ZoneIdDeserializer;
import cloud.cleo.squareup.json.ZonedSerializer;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.LexV2Event;
import com.amazonaws.services.lambda.runtime.events.LexV2Event.DialogAction;
import com.amazonaws.services.lambda.runtime.events.LexV2Event.Intent;
import com.amazonaws.services.lambda.runtime.events.LexV2Event.SessionState;
import com.amazonaws.services.lambda.runtime.events.LexV2Response;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatFunctionCall;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.service.FunctionExecutor;
import com.theokanning.openai.service.OpenAiService;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;

/**
 *
 * @author sjensen
 */
public class ChatGPTLambda implements RequestHandler<LexV2Event, LexV2Response> {

    // Initialize the Log4j logger.
    Logger log = LogManager.getLogger();

    final static ObjectMapper mapper;

    final static TableSchema<ChatGPTSessionState> schema = TableSchema.fromBean(ChatGPTSessionState.class);

    final static DynamoDbEnhancedClient enhancedClient = DynamoDbEnhancedClient.builder().build();

    final static DynamoDbTable<ChatGPTSessionState> sessionState = enhancedClient.table(System.getenv("SESSION_TABLE_NAME"), schema);

    final static OpenAiService open_ai_service = new OpenAiService(System.getenv("OPENAI_API_KEY"), Duration.ofSeconds(25));
    final static String OPENAI_MODEL = System.getenv("OPENAI_MODEL");

    //final static Pattern TRANSFER_PATTERN = Pattern.compile(".*TRANSFER\\s*(\\+\\d{11}).*", Pattern.DOTALL);

    public final static String TRANSFER_FUNCTION_NAME = "transfer_call";
    public final static String HANGUP_FUNCTION_NAME = "hangup_call";
    
    static {
        // Build up the mapper 
        mapper = new ObjectMapper();
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
        // Add module for inputs
        SimpleModule module = new SimpleModule();
        // Serializers
        module.addSerializer(ZonedDateTime.class, new ZonedSerializer());
        module.addSerializer(LocalTime.class, new LocalTimeSerializer());
        module.addSerializer(LocalDate.class, new LocalDateSerializer());
        module.addSerializer(Duration.class, new DurationSerializer());

        // Deserializers for Input Types
        module.addDeserializer(LocalTime.class, new LocalTimeDeserializer());
        module.addDeserializer(LocalDate.class, new LocalDateDeserializer());
        module.addDeserializer(ZoneId.class, new ZoneIdDeserializer());
        module.addDeserializer(Duration.class, new DurationDeserializer());

        mapper.registerModule(module);

        // Create and init all the functions in the package
        AbstractFunction.init();
    }

    @Override
    public LexV2Response handleRequest(LexV2Event lexRequest, Context cntxt) {

        try {
            log.debug(mapper.valueToTree(lexRequest).toString());
            final var intentName = lexRequest.getSessionState().getIntent().getName();
            log.debug("Intent: " + intentName);

            return switch (intentName) {
                default ->
                    processGPT(lexRequest);
            };

        } catch (Exception e) {
            log.error(e);
            // Unhandled Exception
            return buildResponse(lexRequest, "Sorry, I'm having a problem fulfilling your request.  Chat GPT might be down, Please try again later.");
        }
    }

    private LexV2Response processGPT(LexV2Event lexRequest) {

        final var input = lexRequest.getInputTranscript();
        final var localId = lexRequest.getBot().getLocaleId();
        final var inputMode = LexInputMode.fromString(lexRequest.getInputMode());
        final var attrs = lexRequest.getSessionState().getSessionAttributes();
        
        log.debug("Java Locale is " + localId);

        if (input == null || input.isBlank()) {
            log.debug("Got blank input, so just silent or nothing");

            var count = Integer.valueOf(attrs.getOrDefault("blankCounter", "0"));
            count++;

            if (count > 2) {
                log.debug("Two blank responses, sending to Quit Intent");
                // Hang up on caller after 2 silience requests
                return buildQuitResponse(lexRequest);
            } else {
                attrs.put("blankCounter", count.toString());
                // If we get slience (timeout without speech), then we get empty string on the transcript
                return buildResponse(lexRequest, "I'm sorry, I didn't catch that, if your done, simply say good bye, otherwise tell me how I can help?");
            }
        } else {
            // The Input is not blank, so always put the counter back to zero
            attrs.put("blankCounter", "0");
        }

        // When testing in lex console input will be text, so use session ID, for speech we shoud have a phone via Connect
        final var user_id = lexRequest.getSessionId();

        // Key to record in Dynamo
        final var key = Key.builder().partitionValue(user_id).sortValue(LocalDate.now(ZoneId.of("America/Chicago")).toString()).build();

        //  load session state if it exists
        log.debug("Start Retreiving Session State");
        var session = sessionState.getItem(key);
        log.debug("End Retreiving Session State");

        if (session == null) {
            session = new ChatGPTSessionState(user_id,inputMode);
        }

        // Since we can call and change language during session, always specifiy how we want responses
        //session.addSystemMessage(lang.getString(CHATGPT_RESPONSE_LANGUAGE));
        // add this request to the session
        session.addUserMessage(input);

        String botResponse;
        // Set for special case of transfer and hangup
        ChatFunctionCall transferCall = null, hangupCall = null;
        try {
            FunctionExecutor functionExecutor = AbstractFunction.getFunctionExecuter(inputMode);
            functionExecutor.setObjectMapper(mapper);

            while (true) {
                final var chatMessages = session.getChatMessages();
                ChatCompletionRequest request = ChatCompletionRequest.builder()
                        .messages(chatMessages)
                        .model(OPENAI_MODEL)
                        .maxTokens(500)
                        .temperature(0.2) // More focused
                        .n(1) // Only return 1 completion
                        .functions(functionExecutor.getFunctions())
                        .functionCall(ChatCompletionRequest.ChatCompletionRequestFunctionCall.of("auto"))
                        .build();

                log.debug(chatMessages);
                log.debug("Start API Completion Call to ChatGPT");
                final var completion = open_ai_service.createChatCompletion(request);
                log.debug("End API Completion Call to ChatGPT");
                log.debug(completion);

                ChatMessage responseMessage = completion.getChoices().get(0).getMessage();
                botResponse = completion.getChoices().get(0).getMessage().getContent();

                // Add response to session
                session.addMessage(responseMessage);

                ChatFunctionCall functionCall = responseMessage.getFunctionCall();
                if (functionCall != null) {
                    log.debug("Trying to execute " + functionCall.getName() + "...");

                    // Track these special case functions because executing them does nothing really, we just need to know they were called
                    switch (functionCall.getName() ) {
                        case TRANSFER_FUNCTION_NAME -> transferCall = functionCall;
                        case HANGUP_FUNCTION_NAME -> hangupCall = functionCall;
                    }

                    Optional<ChatMessage> message = functionExecutor.executeAndConvertToMessageSafely(functionCall);
                    /* You can also try 'executeAndConvertToMessage' inside a try-catch block, and add the following line inside the catch:
                "message = executor.handleException(exception);"
                The content of the message will be the exception itself, so the flow of the conversation will not be interrupted, and you will still be able to log the issue. */

                    if (message.isPresent()) {
                        /* At this point:
                    1. The function requested was found
                    2. The request was converted to its specified object for execution (Weather.class in this case)
                    3. It was executed
                    4. The response was finally converted to a ChatMessage object. */

                        log.debug("Executed " + functionCall.getName() + ".");
                        session.addMessage(message.get());
                        continue;
                    } else {
                        log.debug("Something went wrong with the execution of " + functionCall.getName() + "...");
                        try {
                            functionExecutor.executeAndConvertToMessage(functionCall);
                        } catch (Exception e) {
                            log.error("Funtion call error", e);
                            return buildResponse(lexRequest, "FunctionCall Error: " + e.getMessage());
                        }
                    }
                }
                break;
            }

            // Save the session to dynamo
            log.debug("Start Saving Session State");
            session.incrementCounter();
            sessionState.putItem(session);
            log.debug("End Saving Session State");
        } catch (RuntimeException rte) {
            if (rte.getCause() != null && rte.getCause() instanceof SocketTimeoutException) {
                log.error("Response timed out", rte);
                botResponse = "The operation timed out, please ask your question again";
            } else {
                throw rte;
            }
        }

        log.debug("botResponse is [" + botResponse + "]");

        // Check to see if GPT tried to call TRANSFER function
        if (transferCall != null) {
            return buildTransferResponse(lexRequest, transferCall.getArguments().findValue("phone_number").asText(), botResponse);
        }


        // Check to see if the GPT says the conversation is done
        if (hangupCall != null) {
            return buildQuitResponse(lexRequest);
        }

        // Since we have a general response, add message asking if there is anything else
        if (!TEXT.equals(inputMode)) {
            // Only add if not text (added to voice response)
            if (!botResponse.endsWith("?")) {  // If ends with question, then we don't need to further append question
                botResponse = botResponse + "  What else can I help you with?";
            }
        }

        return buildResponse(lexRequest, botResponse);
    }

    /**
     * Response that sends you to the Quit intent so the call can be ended
     *
     * @param lexRequest
     * @param response
     * @return
     */
    private LexV2Response buildQuitResponse(LexV2Event lexRequest) {

        // State to return
        final var ss = SessionState.builder()
                // Retain the current session attributes
                .withSessionAttributes(lexRequest.getSessionState().getSessionAttributes())
                // Send back Quit Intent
                .withIntent(Intent.builder().withName("Quit").withState("ReadyForFulfillment").build())
                // Indicate the state
                .withDialogAction(DialogAction.builder().withType("Delegate").build())
                .build();

        final var lexV2Res = LexV2Response.builder()
                .withSessionState(ss)
                .build();
        log.debug("Response is " + mapper.valueToTree(lexV2Res));
        return lexV2Res;
    }

    private LexV2Response buildTransferResponse(LexV2Event lexRequest, String transferNumber, String botResponse) {

        final var attrs = lexRequest.getSessionState().getSessionAttributes();
        attrs.put("transferNumber", transferNumber);
        if (botResponse != null) {
            // Check to make sure transfer is not in the response
            if (botResponse.contains(TRANSFER_FUNCTION_NAME) ) {
                botResponse = botResponse.replace(TRANSFER_FUNCTION_NAME, "");
            }
            if (botResponse.contains(transferNumber)) {
                botResponse = botResponse.replace(transferNumber, "");
            }
            // Sometime GPT also adds HANGUP to the transfer for some reason, so clean it out as well if necessary
            if (botResponse.contains(HANGUP_FUNCTION_NAME)) {
                botResponse = botResponse.replace(HANGUP_FUNCTION_NAME, "");
            }

            botResponse = botResponse.trim();
            if (!botResponse.isBlank()) {
                attrs.put("botResponse", botResponse.split("\n")[0]);
            }
        }

        log.debug("Responding with transfer Intent with transfer number [" + transferNumber + "]");
        log.debug("Bot Response is " + botResponse);
        
        // State to return
        final var ss = SessionState.builder()
                // Retain the current session attributes
                .withSessionAttributes(attrs)
                // Send back Quit Intent
                .withIntent(Intent.builder().withName("Transfer").withState("ReadyForFulfillment").build())
                // Indicate the state
                .withDialogAction(DialogAction.builder().withType("Delegate").build())
                .build();

        final var lexV2Res = LexV2Response.builder()
                .withSessionState(ss)
                .build();
        log.debug("Response is " + mapper.valueToTree(lexV2Res));
        return lexV2Res;
    }

    /**
     * General Response used to send back a message and Elicit Intent again at
     * LEX
     *
     * @param lexRequest
     * @param response
     * @return
     */
    private LexV2Response buildResponse(LexV2Event lexRequest, String response) {

        // State to return
        final var ss = SessionState.builder()
                // Retain the current session attributes
                .withSessionAttributes(lexRequest.getSessionState().getSessionAttributes())
                // Always ElictIntent, so you're back at the LEX Bot looking for more input
                .withDialogAction(DialogAction.builder().withType("ElicitIntent").build())
                .build();

        final var lexV2Res = LexV2Response.builder()
                .withSessionState(ss)
                // We are using plain text responses
                .withMessages(new LexV2Response.Message[]{new LexV2Response.Message("PlainText", response, null)})
                .build();
        log.debug("Response is " + mapper.valueToTree(lexV2Res));
        return lexV2Res;
    }
}
