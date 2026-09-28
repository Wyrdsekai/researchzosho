package org.researchzosho.librarian;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.researchzosho.Config;
import org.researchzosho.drive.aws.AwsCredentials;
import org.researchzosho.drive.aws.Bedrock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.io.IOException;
/**
 * {@code researchzosho bedrock …}: using the models of one's own AWS account. It shows what the account offers, tries one
 * model with one small request so that a missing permission shows now and not in the middle of a research run, and writes the choice
 * into the settings. Every message is written for someone who uses AWS through a sign-in somebody else set up.
 */
final class BedrockCli {

    private BedrockCli() { }

    static final String USAGE = """
            researchzosho bedrock models [--region <r>] [--profile <p>]      the models your AWS account offers in a region
            researchzosho bedrock test <model> [--region <r>] [--profile <p>] one small request to that model, to see that you are allowed to use it
            researchzosho bedrock use <model> [--region <r>] [--profile <p>] [--embed [<embedding model>]]
                                                                         test it, then make it the model this library uses
            Before any of these, sign in to AWS the way you usually do: `aws sso login`, or `aws configure` once for keys.""";

    static int run(String[] args) {
        String verb = args.length > 2 ? args[2] : "";
        String region = "", profile = "", embed = null;
        List<String> rest = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--region") && i + 1 < args.length) region = args[++i];
            else if (args[i].equals("--profile") && i + 1 < args.length) profile = args[++i];
            else if (args[i].equals("--embed")) embed = i + 1 < args.length && !args[i + 1].startsWith("--") ? args[++i] : "amazon.titan-embed-text-v2:0";
            else rest.add(args[i]);
        }
        final String givenRegion = region, givenProfile = profile;
        try {
            Bedrock.Settings s = Bedrock.settings(givenRegion.isBlank() ? "bedrock" : "bedrock:" + givenRegion, k -> k.endsWith("PROFILE") && !givenProfile.isBlank() ? givenProfile : Config.get(k), System.getenv());
            Bedrock bedrock = new Bedrock(s);
            switch (verb) {
                case "models" -> { return models(bedrock); }
                case "test" -> { if (rest.isEmpty()) break; return test(bedrock, rest.get(0), null) ? 0 : 1; }
                case "use" -> {
                    if (rest.isEmpty()) break;
                    if (!test(bedrock, rest.get(0), embed)) { System.out.println("\nNothing was changed in your settings."); return 1; }
                    Config.set("drive", "bedrock"); Config.set("bedrock.region", s.region()); Config.set("model", rest.get(0));
                    if (!s.profile().isBlank()) Config.set("bedrock.profile", s.profile());
                    if (embed != null) { Config.set("embed", "bedrock"); Config.set("embed.model", embed); }
                    System.out.println(used(rest.get(0), s.region(), s.profile(), embed, Service.os()));
                    return 0;
                }
                default -> { }
            }
        } catch (Bedrock.Refused | AwsCredentials.Unavailable e) { System.out.println(e.getMessage()); return 1; }
        catch (IOException e) { System.out.println("The settings could not be written: " + e.getMessage()); return 1; }
        System.err.println(USAGE);
        return 2;
    }

    /** What `bedrock use` says once the model is the library's: what changed, and the commands to give next, each one a command that works. */
    static String used(String model, String region, String profile, String embed, Service.Os os) {
        return "\nYour library now uses " + model + " on Amazon Bedrock, in the region " + region + (profile.isBlank() ? "" : ", with your AWS profile " + profile) + "."
                + (embed != null ? "\nIt also uses " + embed + " to compare texts by meaning. Texts that were indexed with another model have to be indexed again. This command does that, and takes about 20 minutes for a thousand entries:\n    researchzosho rebuild" : "")
                + "\nNothing of your AWS sign-in was saved here: each time, the library asks the AWS command line for it. What you are charged for the model is on your AWS bill."
                + "\nIf the library's service is running, restart it so that it uses the new model:\n" + restart(os);
    }

    /** The commands that restart the library's service on this system, one on each line. */
    static String restart(Service.Os os) {
        return switch (os) {
            case linux -> "    systemctl --user restart " + Service.NAME;
            case macos -> "    launchctl kickstart -k gui/" + Service.uid() + "/" + Service.MAC_LABEL;
            case windows -> "    researchzosho service uninstall\n    researchzosho service install\nThe first stops the service and the second starts it again. If you gave --port or --host when you installed it, give them again.";
        };
    }

    private static int models(Bedrock bedrock) {
        List<Bedrock.Model> all = bedrock.models();
        System.out.println("These are the models Amazon Bedrock offers your AWS account in the region " + bedrock.settings().region() + ". Whether you may USE one is decided in your AWS account; `researchzosho bedrock test <model>` finds out.\n");
        String[][] groups = {{"Models that write and reason (use one as the library's model)", "chat"}, {"Inference profiles (some models are called only through one of these: use the profile's id as the model)", "profile"}, {"Models that compare texts by meaning (for --embed)", "embed"}};
        for (String[] g : groups) {
            List<Bedrock.Model> of = all.stream().filter(m -> g[1].equals("profile") ? m.profile() : g[1].equals("embed") ? m.embeds() : !m.profile() && !m.embeds()).toList();
            if (of.isEmpty()) continue;
            System.out.println(g[0] + ":");
            for (Bedrock.Model m : of) System.out.println("  " + m.id() + "   " + m.name() + (m.profile() ? "" : " (" + m.provider() + ")") + (m.readsPictures() ? ", reads pictures" : "") + (m.throughProfileOnly() ? ", only through an inference profile" : ""));
            System.out.println();
        }
        if (all.isEmpty()) System.out.println("Bedrock listed no models for this region. Try another region with --region, for example us-east-1 or us-west-2.");
        else System.out.println("To try one and then use it:\n\n    researchzosho bedrock use <the id from the list> --embed\n");
        return 0;
    }

    /** One small request, and one small embedding when asked: what a research run will need, found out now. */
    private static boolean test(Bedrock bedrock, String model, String embedModel) {
        ObjectMapper j = new ObjectMapper();
        System.out.println("Asking " + model + " on Bedrock (" + bedrock.settings().region() + ") for one short answer …");
        try {
            ObjectNode body = j.createObjectNode();
            body.put("max_tokens", 200);
            body.putArray("messages").addObject().put("role", "user").put("content", "Use the tool to say that you are ready.");
            ObjectNode f = body.putArray("tools").addObject().put("type", "function").putObject("function");
            f.put("name", "ready"); f.put("description", "Say that the model is ready."); f.putObject("parameters").put("type", "object").putObject("properties").putObject("note").put("type", "string");
            ObjectNode reply = bedrock.chat(model, body, Duration.ofSeconds(120));
            boolean usedTool = reply.path("choices").path(0).path("message").path("tool_calls").size() > 0;
            System.out.println("  It answered" + (usedTool ? ", and it can use tools, which research runs need." : ". It did NOT use the tool it was offered: research runs depend on tools, so they may work poorly with this model. Claude, Llama 3.1 and later, Nova, Qwen and Mistral Large use tools."));
            System.out.println("  The library will treat its memory for one conversation as " + Bedrock.contextWindow(model, Config.getInt("RESEARCHZOSHO_BEDROCK_CONTEXT", 0)) + " tokens. If you know it to be different, put `bedrock.context = <number>` in the settings.");
        } catch (Bedrock.Refused | AwsCredentials.Unavailable e) { System.out.println("  It did not work. " + e.getMessage()); return false; }
        if (embedModel != null) {
            System.out.println("Asking " + embedModel + " for one vector …");
            try { float[] v = bedrock.embed(embedModel, "a short text", Config.getInt("RESEARCHZOSHO_EMBED_DIMENSIONS", 1024)); System.out.println("  It answered with " + v.length + " numbers."); }
            catch (Bedrock.Refused | AwsCredentials.Unavailable e) { System.out.println("  It did not work. " + e.getMessage() + "\n  The library works without it, comparing texts by their words. Leave --embed out to go on without it."); return false; }
        }
        return true;
    }
}
