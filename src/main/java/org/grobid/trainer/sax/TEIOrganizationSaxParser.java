package org.grobid.trainer.sax;

import org.grobid.core.analyzers.GrobidAnalyzer;
import org.grobid.core.lang.Language;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.utilities.UnicodeUtil;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.collections4.CollectionUtils.isEmpty;

/**
 * SAX parser for the XML format for the organization data.
 * Segmentation of tokens must be identical as the one from pdf2xml files to that
 * training and online input tokens are identical.
 *
 *
 * Tanti, 2021
 */

public class TEIOrganizationSaxParser extends DefaultHandler {

    private StringBuffer accumulator = new StringBuffer(); // Accumulate parsed text
    private StringBuffer allContent = new StringBuffer();

    private String output = null;
    private String currentTag = null;

    private List<String> labeled = null; // store token by token the labels
    private List<List<String>> allLabeled = null; // list of labels
    private List<LayoutToken> tokens = null;
    private List<List<LayoutToken>> allTokens = null; // list of LayoutToken segmentation
    public int nbOrganizations = 0;

    public TEIOrganizationSaxParser() {
        allTokens = new ArrayList<List<LayoutToken>>();
        allLabeled = new ArrayList<List<String>>();
    }

    public void characters(char[] buffer, int start, int length) {
        accumulator.append(buffer, start, length);
        if (allContent != null) {
            allContent.append(buffer, start, length);
        }
    }

    public String getText() {
        return accumulator.toString().trim();
    }

    public List<List<String>> getLabeledResult() {
        return allLabeled;
    }

    public List<List<LayoutToken>> getTokensResult() {
        return allTokens;
    }

    public void endElement(String uri,
                           String localName,
                           String qName) throws SAXException {
        qName = qName.toLowerCase();

        if ((qName.equals("orgname")) || (qName.equals("address")) || (qName.equals("country")) || (qName.equals("settlement")) ||
            (qName.equals("email")) ||  (qName.equals("phone")) || (qName.equals("fax")) || (qName.equals("web")) ||
            (qName.equals("note")) || (qName.equals("ptr"))
        ) {
            String text = getText();
            writeField(text);
        } else if (qName.equals("lb")) {
            // we note a line break
            accumulator.append(" +L+ ");
        } else if (qName.equals("pb")) {
            accumulator.append(" +PAGE+ ");
        } else if (qName.equals("org")) {
            String text = getText();
            currentTag = "<other>";
            if (text.length() > 0) {
                writeField(text);
            }
            nbOrganizations++;
            allLabeled.add(labeled);
            allTokens.add(tokens);
            allContent = null;
        }

        accumulator.setLength(0);
    }

    public void startElement(String namespaceURI,
                             String localName,
                             String qName,
                             Attributes atts)
        throws SAXException {
        String text = getText();
        if (text.length() > 0) {
            currentTag = "<other>";
            writeField(text);
        }
        accumulator.setLength(0);

        qName = qName.toLowerCase();
        if (qName.equals("orgname")) {
            int length = atts.getLength();
            if (length > 0) {
                // Process each attribute
                for (int i = 0; i < length; i++) {
                    // Get names and values for each attribute
                    String name = atts.getQName(i);
                    String value = atts.getValue(i);

                    if (name != null) {
                        if (name.equals("type")) {
                            if (value.equals("ghu")) {
                                currentTag = "<ghu>";
                            } else if (value.equals("chu")) {
                                currentTag = "<chu>";
                            } else if (value.equals("dmu")) {
                                currentTag = "<dmu>";
                            } else if (value.equals("pole")) {
                                currentTag = "<pole>";
                            } else if (value.equals("site")) {
                                currentTag = "<site>";
                            } else if (value.equals("institution")) {
                                currentTag = "<institution>";
                            } else if (value.equals("university")) {
                                currentTag = "<university>";
                            } else if (value.equals("hospital")) {
                                currentTag = "<hospital>";
                            } else if (value.equals("center")) {
                                currentTag = "<center>";
                            } else if (value.equals("service")) {
                                currentTag = "<service>";
                            } else if (value.equals("department")) {
                                currentTag = "<department>";
                            } else if (value.equals("unit")) {
                                currentTag = "<unit>";
                            } else
                                currentTag = "<organization>"; // we take org with the type other than center, service, department or administration as <org>
                        }
                    }
                }
            } else
                currentTag = "<organization>"; // we take orgName without any attribute
        }  else if (qName.equals("address")) {
            currentTag = "<address>";
        } else if (qName.equals("country")) {
            currentTag = "<country>";
        } else if (qName.equals("settlement")) {
            currentTag = "<settlement>";
        } else if (qName.equals("email")) {
            currentTag = "<email>";
        } else if (qName.equals("phone")) {
            currentTag = "<phone>";
        } else if (qName.equals("fax")) {
            currentTag = "<fax>";
        } else if (qName.equals("ptr")) {
            int length = atts.getLength();

            // Process each attribute
            for (int i = 0; i < length; i++) {
                // Get names and values for each attribute
                String name = atts.getQName(i);
                String value = atts.getValue(i);

                if ((name != null) && (value != null)) {
                    if (name.equals("type")) {
                        if (value.equals("web")) {
                            currentTag = "<web>";
                        }
                    }
                }
            }
        } else if (qName.equals("note")) {
            int length = atts.getLength();

            if (length == 0) {
                currentTag = "<note>";
            } else {
                // Process each attribute
                for (int i = 0; i < length; i++) {
                    // Get names and values for each attribute
                    String name = atts.getQName(i);
                    String value = atts.getValue(i);

                    if ((name != null) && (value != null)) {
                        if (name.equals("type")) {
                            if (value.equals("organization")) {
                                currentTag = "<note>";
                            }
                        }
                    }
                }
            }
        } else if (qName.equals("org")) {
            accumulator = new StringBuffer();
            allContent = new StringBuffer();
            labeled = new ArrayList<String>();
            tokens = new ArrayList<LayoutToken>();
        }
        accumulator.setLength(0);
    }

    private void writeField(String text) {
        if (tokens == null) {
            // nothing to do, text must be ignored
            return;
        }

        // we segment the text
        List<LayoutToken> localTokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(text);

        if (isEmpty(localTokens)) {
            localTokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(text, new Language("en", 1.0));
        }

        if (isEmpty(localTokens)) {
            return;
        }

        boolean begin = true;
        for (LayoutToken token : localTokens) {
            tokens.add(token);
            String content = token.getText();
            if (content.equals(" ") || content.equals("\n")) {
                labeled.add(null);
                continue;
            }

            content = UnicodeUtil.normaliseTextAndRemoveSpaces(content);
            if (content.trim().length() == 0) {
                labeled.add(null);
                continue;
            }

            if (content.length() > 0) {
                if (begin) {
                    labeled.add("I-" + currentTag);
                    begin = false;
                } else {
                    labeled.add(currentTag);
                }
            }
        }
    }
}