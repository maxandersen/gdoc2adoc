//usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS info.picocli:picocli:4.2.0
//DEPS com.google.api-client:google-api-client:1.30.1
//DEPS com.google.oauth-client:google-oauth-client-jetty:1.30.1
//DEPS com.google.apis:google-api-services-docs:v1-rev20190827-1.30.1
//DEPS com.fasterxml.jackson.core:jackson-databind:2.9.8
// com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.9.8


import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.docs.v1.Docs;
import com.google.api.services.docs.v1.DocsScopes;
import com.google.api.services.docs.v1.model.Document;
import com.google.api.services.docs.v1.model.Link;
import com.google.api.services.docs.v1.model.Paragraph;
import com.google.api.services.docs.v1.model.ParagraphElement;
import com.google.api.services.docs.v1.model.ParagraphStyle;
import com.google.api.services.docs.v1.model.StructuralElement;
import com.google.api.services.docs.v1.model.Table;
import com.google.api.services.docs.v1.model.TableOfContents;
import com.google.api.services.docs.v1.model.TextRun;
import com.google.api.services.docs.v1.model.TextStyle;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

//import static java.lang.System.*;

@Command(name = "gdoc2adoc", mixinStandardHelpOptions = true, version = "gdoc2adoc 0.1",
        description = "gdoc2adoc made with jbang")
class gdoc2adoc implements Callable<Integer> {

    @Parameters(index = "0", description = "Document id or url to convert", arity = "1")
    private String documentid;

    @Option(names = {"--credentials"}, defaultValue = "credentials.json")
    private File credentials;

    @Option(names = {"--dump"}, description = "If enabled print document structure to output")
    boolean dump;

    private static final String APPLICATION_NAME = "gdoc2adoc";
    private static final JsonFactory JSON_FACTORY = JacksonFactory.getDefaultInstance();
    private static final String TOKENS_DIRECTORY_PATH = "tokens";

    /**
     * Global instance of the scopes required by this sample. If modifying these scopes, delete
     * your previously saved tokens/ folder.
     */
    private static final List<String> SCOPES =
            Collections.singletonList(DocsScopes.DOCUMENTS_READONLY);

    /**
     * Creates an authorized Credential object.
     *
     * @param HTTP_TRANSPORT The network HTTP Transport.
     * @return An authorized Credential object.
     * @throws IOException If the credentials.json file cannot be found.
     */
    private Credential getCredentials(final NetHttpTransport HTTP_TRANSPORT)
            throws IOException {
        // Load client secrets.
        GoogleClientSecrets secrets =
                GoogleClientSecrets.load(JSON_FACTORY, new FileReader(credentials));

        // Build flow and trigger user authorization request.
        GoogleAuthorizationCodeFlow flow =
                new GoogleAuthorizationCodeFlow.Builder(HTTP_TRANSPORT, JSON_FACTORY, secrets, SCOPES)
                        .setDataStoreFactory(new FileDataStoreFactory(new java.io.File(TOKENS_DIRECTORY_PATH)))
                        .setAccessType("offline")
                        .build();
        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }

    public static void main(String... args) {
        int exitCode = new CommandLine(new gdoc2adoc()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception { // your business logic goes here...
        // Build a new authorized API client service.
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Docs docsService =
                new Docs.Builder(HTTP_TRANSPORT, JSON_FACTORY, getCredentials(HTTP_TRANSPORT))
                        .setApplicationName(APPLICATION_NAME)
                        .build();


        documentid =  documentid.replaceAll("^.*/document/d/([a-zA-Z0-9-_]+)/?.*$",
                "$1");

        try {
            Document doc = docsService.documents().get(documentid).execute();

            var v = new GDocVisitor();

            if(dump) {

                ObjectMapper mapper = new ObjectMapper(new com.fasterxml.jackson.core.JsonFactory());
                System.out.println(mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc));

            } else {
               System.out.println(v.visit(doc));
            }

            //Gson gson = new GsonBuilder().setPrettyPrinting().create();


            //System.out.println(gson.toJson(response));
        } catch(GoogleJsonResponseException e) {
            throw new IllegalStateException("Error fetching document id " + documentid, e);
        }
        return 0;
    }
}

class GDocVisitor {

    public GDocVisitor() {

    }

    public StringBuffer visit(Document doc) {
        List<StructuralElement> content = doc.getBody().getContent();
        return visit(content);
    }

    public StringBuffer visit(List<StructuralElement> elements) {
        StringBuffer buf = new StringBuffer();
        for (StructuralElement element : elements) {
            buf.append(visit(element));
        }
        return buf;
    }

    public StringBuffer visit(StructuralElement element) {
        var buf = new StringBuffer();
        if (element.getParagraph() != null) {
            buf.append(visit(element.getParagraph()));
        } else if (element.getTable() != null) {
            buf.append(visit(element.getTable()));
        } else if (element.getTableOfContents() != null) {
            buf.append(visit(element.getTableOfContents()));
        }
        return buf;
    }

    Map<String, String> type2Prefix = new HashMap<>() {
        {
            put("TITLE", header(1));
            put("SUBTITLE", header(2));

            put("HEADING_1", header(2));
            put("HEADING_2", header(3));
            put("HEADING_3", header(4));
            put("HEADING_4", header(5));
            put("HEADING_5", header(6));
            put("HEADING_6", header(7));

        }
    };

    private String header(int i) {
        var buf = new StringBuffer();
        while(i>0) {
            buf.append("=");
            i--;
        }
        return buf.toString();
    }

    public StringBuffer visit(Paragraph element) {
        StringBuffer buf = new StringBuffer();

        StringBuffer contentbuf = new StringBuffer();
        for (ParagraphElement paragraphElement : element.getElements()) {
            contentbuf.append(visit(paragraphElement));
        }

        ParagraphStyle style = element.getParagraphStyle();

        if(style.getHeadingId()!=null) {
          var type = style.getNamedStyleType();
          String indent = type2Prefix.get(type);
          if(indent==null) {
              nyi("namedstyletype: " + type );
          }
          buf.append(indent);
          buf.append(" ");
        }

        if(!contentbuf.toString().isBlank()) {
            buf.append(contentbuf);
            buf.append("\n");
            return buf;
        } else {
            contentbuf.append("\n");
            return contentbuf; // blank/empty area
        }

    }

    private StringBuffer visit(TableOfContents element  ) {
        // simply let asciidoctor generate the toc and ignore
        // whatever google have done.
        return new StringBuffer("toc::[]");
    }

    private StringBuffer nyi(String o) {
        System.err.println(o  + " not implemented yet!");
        return new StringBuffer();
    }

    private StringBuffer nyi(Object o) {
        return nyi(o.getClass().getName());
    }

    public StringBuffer visit(Table table) {
        return nyi(table);
    }

    public StringBuffer visit(ParagraphElement element) {
        StringBuffer buf = new StringBuffer();

        var textrun = element.getTextRun();
        if(textrun!=null) {
            buf.append(visit(textrun));
        }
        return buf;
    }

    public StringBuffer visit(TextRun element) {
        StringBuffer buf = new StringBuffer();

        TextStyle style = element.getTextStyle();

        Link link = style.getLink();
        if(link!=null) {
            if(element.getContent().trim().length()>0) {
                String url = link.getUrl();
                buf.append(String.format("%s[%s]", url, element.getContent()));
            } else {
                buf.append(element.getContent()); // should be just empty space, not linking in as asciidoc will then use url as the content.
            }
            return buf; //no additional formatting for links
        }

        var left = new StringBuffer();
        var right = new StringBuffer();
        if(truth(style.getBold())) {
            if(truth(style.getItalic())) {
                // to handle bold italic properly
                left.append("**__");
                right.append("__**");
            } else {
                left.append("*");
                right.append("*");
            }
        } else if(truth(style.getItalic())) {
            left.append("_");
            right.append("_");
        } else if(truth(style.getUnderline())) {
            left.append("[.underline]#");
            right.append("#");
        } else if(truth(style.getStrikethrough())) {
            left.append("[.line-through]#");
            right.append("#");
        }

        String text = element.getContent().replace("\n", ""); // trims to remove newlines
        String postfix = "";
        if(text.length()!=element.getContent().length()) {
            postfix = "\n";
        }

        if(left.length()>0 || right.length()>0) {
            // trim spaces as asciidoc does not format *test *
            //text = text.replaceFirst("\\s+$", "");
            //text = text.replaceFirst("^\\s+", "");
            text = text.trim(); // TODO: is both ends necessary ?
        }


        buf.append(String.format("%s%s%s%s", left, text, right, postfix));
        return buf;

    }

    private boolean truth(Boolean bool) {
        return bool == null ? false : bool;
    }

}
