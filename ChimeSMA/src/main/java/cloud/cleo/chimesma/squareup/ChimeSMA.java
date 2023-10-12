package cloud.cleo.chimesma.squareup;

import cloud.cleo.chimesma.actions.*;
import java.util.Locale;
import java.util.function.Function;

/**
 * IVR for Square Retail using Lex Bot backed by ChatGPT.
 *
 * @author sjensen
 */
public class ChimeSMA extends AbstractFlow {

    private final static Action MAIN_MENU = getMainMenu();

    /**
     * Simple Object that caches Square data about hours to determine whether
     * store is open or closed
     */
    private final static SquareHours squareHours = SquareHours.getInstance();

    /**
     * Main Transfer number used
     */
    private final static String MAIN_NUMBER = System.getenv("MAIN_NUMBER");
    /**
     * Voice Connector ARN so calls to main number will go SIP to PBX
     */
    private final static String VC_ARN = System.getenv("VC_ARN");

    /**
     * Initial action is to play welcome message and whether store is open or
     * closed
     *
     * @return
     */
    @Override
    protected Action getInitialAction() {

        // Play open or closed prompt based on Square Hours  
        final var openClosed = PlayAudioAction.builder()
                .withKeyF(f -> squareHours.isOpen() ? "open.wav" : "closed.wav") // This is always in english
                .withNextAction(MAIN_MENU)
                .withErrorAction(MAIN_MENU)
                .build();

        // Start with a welcome message
        final var welcome = PlayAudioAction.builder()
                .withKey("welcome.wav") // This is always in english
                .withNextAction(openClosed)
                .withErrorAction(openClosed)
                .build();

        return welcome;
    }

    /**
     * Main menu is just a LexBox, and the only outputs are Quit and Transfer.
     * Quit - hang up the call Transfer. - transfer the call to another number.
     *
     * @return
     */
    public static Action getMainMenu() {

        final var english = Locale.forLanguageTag("en-US");
        final var spanish = Locale.forLanguageTag("es-US");

        final var hangup = HangupAction.builder()
                .withDescription("This is my last step").build();

        final var goodbye = PlayAudioAction.builder()
                .withDescription("Say Goodbye")
                .withKeyLocale("goodbye")
                .withNextAction(hangup)
                .build();

        final var lexBotEN = StartBotConversationAction.builder()
                .withDescription("ChatGPT English")
                .withLocale(english)
                .withContent("You can ask about our products, hours, location, or speak to one of our team members. Tell us how we can help today?")
                .build();

        // Will add Spanish later if needed
        final var lexBotES = StartBotConversationAction.builder()
                .withDescription("ChatGPT Spanish")
                .withLocale(spanish)
                .withContent("¿En qué puede ayudarte Chat GPT?")
                .build();

        // Two invocations of the bot, so create one function and use for both
        Function<StartBotConversationAction, Action> botNextAction = (a) -> {
            return switch (a.getIntentName()) {
                case "Transfer" -> {
                    final var attrs = a.getActionData().getIntentResult().getSessionState().getSessionAttributes();
                    // final var botResponse = attrs.get("botResponse");  # Ignore what GPT says, sometimes it says we cannot transfer even though we are
                    final var phone = attrs.get("transferNumber");
                    final var transfer = CallAndBridgeAction.builder()
                            .withDescription("Send Call to Team Member")
                            .withRingbackToneKey("ringing.wav")
                            .withUri(phone)
                            .build();
                    if (phone.equals(MAIN_NUMBER) && !VC_ARN.equalsIgnoreCase("PSTN")) {
                        // We are transferring to main number, so use SIP by sending call to Voice Connector
                        transfer.setArn(VC_ARN);
                        transfer.setDescription("Send Call to Main Number via SIP");
                    }
                    yield PlayAudioAction.builder()
                    .withDescription("Indicate transfer in progress")
                    .withKey("transfer.wav")
                    .withNextAction(transfer)
                    .build();
                }
                case "Quit" ->
                    goodbye;
                default ->
                    SpeakAction.builder()
                    .withText("A system error has occured, please call back and try again")
                    .withNextAction(hangup)
                    .build();
            }; // The Lex bot also has intent to speak with someone
        };

        // Both bots are the same, so the handler is the same
        lexBotEN.setNextActionF(botNextAction);
        lexBotES.setNextActionF(botNextAction);

        return lexBotEN;
    }

    /**
     * When an error occurs on a Action and the Action did not specify an Error
     * Action
     *
     * @return the default error Action
     */
    @Override
    protected Action getErrorAction() {
        final var errMsg = SpeakAction.builder()
                .withText("A system error has occured, please call back and try again")
                .withNextAction(new HangupAction())
                .build();

        return errMsg;
    }

    @Override
    protected void newCallHandler(Action action) {
        log.info("New Call Handler Code Here");
    }

    @Override
    protected void hangupHandler(Action action) {
        log.info("Hangup Handler Code Here");
    }

}
