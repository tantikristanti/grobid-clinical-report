package org.grobid.core.features;

import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.utilities.OffsetPosition;
import org.grobid.core.utilities.TextUtilities;

import java.util.List;
import java.util.regex.Matcher;

/**
 * Class for adding features to dateline chunk.
 */
public class FeaturesVectorDateline {
    public String string = null; // lexical feature
    public String label = null; // label if known
    public String lineStatus = null; // one of LINESTART, LINEIN, LINEEND
    public String capitalisation = null; // one of INITCAP, ALLCAPS, NOCAPS
    public String digit;  // one of ALLDIGIT, CONTAINDIGIT, NODIGIT
    public boolean singleChar = false;
    public boolean year = false;
    public boolean month = false;
    public String punctType = null;
    // one of NOPUNCT, OPENBRACKET, ENDBRACKET, DOT, COMMA, HYPHEN, QUOTE, PUNCT (default)
    public boolean countryName = false;
    public boolean cityName = false;
    public String wordShape = null;

    public String printVector() {
        if (string == null) return null;
        if (string.length() == 0) return null;
        StringBuffer res = new StringBuffer();

        // token string (1)
        res.append(string);

        // lowercase string
        res.append(" " + string.toLowerCase());

        // prefix (4)
        res.append(" " + TextUtilities.prefix(string, 1));
        res.append(" " + TextUtilities.prefix(string, 2));
        res.append(" " + TextUtilities.prefix(string, 3));
        res.append(" " + TextUtilities.prefix(string, 4));

        // suffix (4)
        res.append(" " + TextUtilities.suffix(string, 1));
        res.append(" " + TextUtilities.suffix(string, 2));
        res.append(" " + TextUtilities.suffix(string, 3));
        res.append(" " + TextUtilities.suffix(string, 4));

        // line information (1)
        res.append(" " + lineStatus);

        // capitalisation (1)
        if (digit.equals("ALLDIGIT"))
            res.append(" NOCAPS");
        else
            res.append(" " + capitalisation);

        // digit information (1)
        res.append(" " + digit);

        // character information (1)
        if (singleChar)
            res.append(" 1");
        else
            res.append(" 0");

        // lexical information (4)
        if (year)
            res.append(" 1");
        else
            res.append(" 0");

        if (month)
            res.append(" 1");
        else
            res.append(" 0");

        if (countryName)
            res.append(" 1");
        else
            res.append(" 0");

        if (cityName)
            res.append(" 1");
        else
            res.append(" 0");

        // punctuation information (2)
        res.append(" " + punctType); // in case the token is a punctuation (NO otherwise)

        res.append(" ").append(wordShape);

        // label - for training data (1)
        if (label != null)
            res.append(" " + label + "\n");
        else
            res.append(" 0\n");

        return res.toString();
    }

    /**
     * Add features for the dateline model.
     */
    public static StringBuilder addFeaturesDateline(List<String> lines) throws Exception {
        FeatureFactory featureFactory = FeatureFactory.getInstance();

        String line;
        StringBuilder stringBuilder = new StringBuilder();
        boolean newline = true;
        boolean newBlock = true;
        String currentFont = null;
        int currentFontSize = -1;

        boolean endblock = false;
        String previousTag = null;
        String previousText = null;

        FeaturesVectorDateline features = null;
        for (int n = 0; n < lines.size(); n++) {
            boolean outputLineStatus = false;
            boolean outputBlockStatus = false;

            line = lines.get(n);

            if (line == null) {
                stringBuilder.append(" \n");
                newBlock = true;
                newline = true;
                continue;
            }
            line = line.trim();
            if (line.length() == 0) {
                stringBuilder.append("\n \n");
                newBlock = true;
                newline = true;
                continue;
            }

            if (line.equals("@newline")) {
                if (newline) {
                    newBlock = true;
                }
                newline = true;
                continue;
            }

            int ind = line.indexOf(" ");
            String text = null;
            String tag = null;
            if (ind != -1) {
                text = line.substring(0, ind);
                tag = line.substring(ind + 1, line.length());
            }

            features = new FeaturesVectorDateline();
            features.string = text;

            if (newline) {
                features.lineStatus = "LINESTART";
                outputLineStatus = true;
            }

            Matcher m0 = featureFactory.isPunct.matcher(text);
            if (m0.find()) {
                features.punctType = "PUNCT";
            }

            if ((text.equals("(")) | (text.equals("["))) {
                features.punctType = "OPENBRACKET";
            } else if ((text.equals(")")) | (text.equals("]"))) {
                features.punctType = "ENDBRACKET";
            } else if (text.equals(".")) {
                features.punctType = "DOT";
            } else if (text.equals(",")) {
                features.punctType = "COMMA";
            } else if (text.equals("-")) {
                features.punctType = "HYPHEN";
            } else if (text.equals("\"") | text.equals("\'") | text.equals("`")) {
                features.punctType = "QUOTE";
            }

            if (n == 0) {
                if (!outputLineStatus) {
                    features.lineStatus = "LINESTART";
                    outputLineStatus = true;
                }
            } else if (lines.size() == n + 1) {
                if (!outputLineStatus) {
                    features.lineStatus = "LINEEND";
                    outputLineStatus = true;
                }
            } else {
                boolean endline = false;
                int i = 1;
                boolean endloop = false;
                while ((lines.size() > n + i) && (!endloop)) {
                    String newLine = lines.get(n + i);

                    if (newLine != null) {
                        if (newLine.trim().length() == 0) {
                            endline = true;
                            endblock = true;
                            if (!outputLineStatus) {
                                features.lineStatus = "LINEEND";
                                outputLineStatus = true;
                            }
                        } else if (newLine.equals("@newline")) {
                            endline = true;
                            if (!outputLineStatus) {
                                features.lineStatus = "LINEEND";
                                outputLineStatus = true;
                            }
                        } else {
                            endloop = true;
                        }
                    }

                    if (endline && !outputLineStatus) {
                        features.lineStatus = "LINEEND";
                        outputLineStatus = true;
                    }
                    i++;
                }
            }

            newline = false;
            if (!outputLineStatus) {
                features.lineStatus = "LINEIN";
                outputLineStatus = true;
            }

            if (text.length() == 1) {
                features.singleChar = true;
            }

            if (Character.isUpperCase(text.charAt(0))) {
                features.capitalisation = "INITCAP";
            }

            if (featureFactory.test_all_capital(text)) {
                features.capitalisation = "ALLCAP";
            }

            if (features.capitalisation == null)
                features.capitalisation = "NOCAPS";

            if (featureFactory.test_digit(text)) {
                features.digit = "CONTAINSDIGITS";
            }

            if (featureFactory.test_month(text)) {
                features.month = true;
            }

            Matcher m = featureFactory.isDigit.matcher(text);
            if (m.find()) {
                features.digit = "ALLDIGIT";
            }

            if (features.digit == null)
                features.digit = "NODIGIT";

            Matcher m2 = featureFactory.year.matcher(text);
            if (m2.find()) {
                features.year = true;
            }

            if (featureFactory.test_country(text)) {
                features.countryName = true;
            }

            if (featureFactory.test_city(text)) {
                features.cityName = true;
            }

            if (features.punctType == null)
                features.punctType = "NOPUNCT";

            features.wordShape = TextUtilities.wordShape(text);

            /*if (tag.trim().equals("<dateline>")){
                features.label = "";
            } else {*/
                features.label = tag;
            //}

            stringBuilder.append(features.printVector());

            previousTag = tag;
            previousText = text;

        }

        return stringBuilder;
    }

}