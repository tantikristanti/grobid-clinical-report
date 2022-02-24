package org.grobid.core.engines;

import com.google.common.collect.Iterables;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.grobid.core.GrobidModels;
import org.grobid.core.analyzers.GrobidAnalyzer;
import org.grobid.core.data.*;
import org.grobid.core.document.*;
import org.grobid.core.engines.citations.CalloutAnalyzer;
import org.grobid.core.engines.citations.CalloutAnalyzer.MarkerType;
import org.grobid.core.engines.config.GrobidAnalysisConfig;
import org.grobid.core.engines.label.MedicalLabels;
import org.grobid.core.engines.label.TaggingLabel;
import org.grobid.core.engines.label.TaggingLabels;
import org.grobid.core.engines.tagging.GenericTaggerUtils;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.exceptions.GrobidResourceException;
import org.grobid.core.features.*;
import org.grobid.core.lang.Language;
import org.grobid.core.layout.*;
import org.grobid.core.lexicon.Lexicon;
import org.grobid.core.tokenization.TaggingTokenCluster;
import org.grobid.core.tokenization.TaggingTokenClusteror;
import org.grobid.core.utilities.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * A class for extracting full text of medical reports.
 * This class is taken and adapted from the FullTextParser class of Grobid (@author Patrice Lopez)
 * <p>
 * Tanti, 2021
 */
public class FullMedicalTextParser extends AbstractParser {
    private static final Logger LOGGER = LoggerFactory.getLogger(FullMedicalTextParser.class);

    protected File tmpPath = null;

    // default bins for relative position
    private static final int NBBINS_POSITION = 12;

    // default bins for inter-block spacing
    private static final int NBBINS_SPACE = 5;

    // default bins for block character density
    private static final int NBBINS_DENSITY = 5;

    // projection scale for line length
    private static final int LINESCALE = 10;

    protected EngineMedicalParsers parsers;

    private Lexicon lexicon = Lexicon.getInstance();

    /**
     *
     */
    public FullMedicalTextParser(EngineMedicalParsers parsers) {
        super(GrobidModels.FULL_MEDICAL_TEXT);
        this.parsers = parsers;
        tmpPath = GrobidProperties.getTempPath();
    }

    public Document processing(File inputPdf,
                               GrobidAnalysisConfig config) throws Exception {
        DocumentSource documentSource =
            DocumentSource.fromPdf(inputPdf, config.getStartPage(), config.getEndPage(),
                config.getPdfAssetPath() != null, true, false);
        return processing(documentSource, config);
    }

    public Document processing(File inputPdf,
                               String md5Str,
                               GrobidAnalysisConfig config) throws Exception {
        DocumentSource documentSource =
            DocumentSource.fromPdf(inputPdf, config.getStartPage(), config.getEndPage(),
                config.getPdfAssetPath() != null, true, false);
        documentSource.setMD5(md5Str);
        return processing(documentSource, config);
    }

    /**
     * Machine-learning recognition of the complete full text structures.
     *
     * @param documentSource input
     * @param config         config
     * @return the document object with built TEI
     */
    public Document processing(DocumentSource documentSource,
                               GrobidAnalysisConfig config) {
        if (tmpPath == null) {
            throw new GrobidResourceException("Cannot process pdf file, because temp path is null.");
        }
        if (!tmpPath.exists()) {
            throw new GrobidResourceException("Cannot process pdf file, because temp path '" +
                tmpPath.getAbsolutePath() + "' does not exists.");
        }
        try {
            // general segmentation
            Document doc = parsers.getMedicalReportSegmenterParser().processing(documentSource, config);
            SortedSet<DocumentPiece> documentBodyParts = doc.getDocumentPart(MedicalLabels.BODY);

            // header processing
            HeaderMedicalItem resHeader = new HeaderMedicalItem();

            // left-note processing
            LeftNoteMedicalItem resLeftNote = new LeftNoteMedicalItem();

            String strLeftNote = null;

            Pair<String, LayoutTokenization> featSeg = null;

            // using the segmentation model to identify the header and left-note zones
            parsers.getHeaderMedicalParser().processingHeaderLeftNoteSection(config, doc, resHeader, resLeftNote, strLeftNote, false);

            // full text processing
            featSeg = getBodyTextFeatured(doc, documentBodyParts);
            String resultBody = null;
            LayoutTokenization layoutTokenization = null;
            List<Figure> figures = null;
            List<Table> tables = null;
            if (featSeg != null && isNotBlank(featSeg.getLeft())) {
                // if featSeg is null, it usually means that no body segment is found in the
                // document segmentation
                String bodytext = featSeg.getLeft(); // features of body tokens
                layoutTokenization = featSeg.getRight();

                // labeling the featured tokens of the body part
                resultBody = label(bodytext);

                // we apply now the figure and table models based on the fulltext labeled output
                figures = processFigures(resultBody, layoutTokenization.getTokenization(), doc);
                // further parse the caption
                for (Figure figure : figures) {
                    if (CollectionUtils.isNotEmpty(figure.getCaptionLayoutTokens())) {
                        Pair<String, List<LayoutToken>> captionProcess = processShort(figure.getCaptionLayoutTokens(), doc);
                        figure.setLabeledCaption(captionProcess.getLeft());
                        figure.setCaptionLayoutTokens(captionProcess.getRight());
                    }
                }

                tables = processTables(resultBody, layoutTokenization.getTokenization(), doc);
                // further parse the caption
                for (Table table : tables) {
                    if (CollectionUtils.isNotEmpty(table.getCaptionLayoutTokens())) {
                        Pair<String, List<LayoutToken>> captionProcess = processShort(table.getCaptionLayoutTokens(), doc);
                        table.setLabeledCaption(captionProcess.getLeft());
                        table.setCaptionLayoutTokens(captionProcess.getRight());
                    }
                    if (CollectionUtils.isNotEmpty(table.getNoteLayoutTokens())) {
                        Pair<String, List<LayoutToken>> noteProcess = processShort(table.getNoteLayoutTokens(), doc);
                        table.setLabeledNote(noteProcess.getLeft());
                        table.setNoteLayoutTokens(noteProcess.getRight());
                    }
                }

            } else {
                LOGGER.debug("Fulltext model: The featured body is empty");
            }

            // possible annexes (view as a piece of full text similar to the body)
            documentBodyParts = doc.getDocumentPart(MedicalLabels.ANNEX);
            featSeg = getBodyTextFeatured(doc, documentBodyParts);
            String resultAnnex = null;
            List<LayoutToken> tokenizationsBody2 = null;
            if (featSeg != null && isNotEmpty(trim(featSeg.getLeft()))) {
                // if featSeg is null, it usually means that no body segment is found in the
                // document segmentation
                String bodytext = featSeg.getLeft();
                tokenizationsBody2 = featSeg.getRight().getTokenization();
                resultAnnex = label(bodytext);
                //System.out.println(rese);
            }

            // post-process reference and footnote callout to keep them consistent (e.g. for example avoid that a footnote
            // callout in superscript is by error labeled as a numerical reference callout)
            List<MarkerType> markerTypes = null;

            if (resultBody != null)
                markerTypes = postProcessCallout(resultBody, layoutTokenization);

            // final combination
            toTEI(doc, // document
                resultBody, resultAnnex, // labeled data for body and annex
                layoutTokenization, tokenizationsBody2, // tokenization for body and annex
                resHeader, // header
                resLeftNote, // left-note information
                strLeftNote,
                figures, tables, markerTypes,
                config);
            return doc;
        } catch (GrobidException e) {
            throw e;
        } catch (Exception e) {
            throw new GrobidException("An exception occurred while running Grobid.", e);
        }
    }

    /**
     * Process a simple segment of layout tokens with the full text model.
     * Return null if provided Layout Tokens is empty or if structuring failed.
     */
    public Pair<String, List<LayoutToken>> processShortNew(List<LayoutToken> tokens, Document doc) {
        if (CollectionUtils.isEmpty(tokens))
            return null;

        SortedSet<DocumentPiece> documentParts = new TreeSet<DocumentPiece>();
        // identify continuous sequence of layout tokens in the abstract
        int posStartPiece = -1;
        int currentOffset = -1;
        int startBlockPtr = -1;
        LayoutToken previousToken = null;
        for (LayoutToken token : tokens) {
            if (currentOffset == -1) {
                posStartPiece = getDocIndexToken(doc, token);
                startBlockPtr = token.getBlockPtr();
            } else if (token.getOffset() != currentOffset + previousToken.getText().length()) {
                // new DocumentPiece to be added 
                DocumentPointer dp1 = new DocumentPointer(doc, startBlockPtr, posStartPiece);
                DocumentPointer dp2 = new DocumentPointer(doc,
                    previousToken.getBlockPtr(),
                    getDocIndexToken(doc, previousToken));
                DocumentPiece piece = new DocumentPiece(dp1, dp2);
                documentParts.add(piece);

                // set index for the next DocumentPiece
                posStartPiece = getDocIndexToken(doc, token);
                startBlockPtr = token.getBlockPtr();
            }
            currentOffset = token.getOffset();
            previousToken = token;
        }
        // we still need to add the last document piece
        // conditional below should always be true because abstract is not null if we reach this part, but paranoia is good when programming 
        if (posStartPiece != -1) {
            DocumentPointer dp1 = new DocumentPointer(doc, startBlockPtr, posStartPiece);
            DocumentPointer dp2 = new DocumentPointer(doc,
                previousToken.getBlockPtr(),
                getDocIndexToken(doc, previousToken));
            DocumentPiece piece = new DocumentPiece(dp1, dp2);
            documentParts.add(piece);
        }

        Pair<String, LayoutTokenization> featSeg = getBodyTextFeatured(doc, documentParts);
        String res = "";
        List<LayoutToken> layoutTokenization = new ArrayList<>();
        if (featSeg != null) {
            String featuredText = featSeg.getLeft();
            LayoutTokenization layouts = featSeg.getRight();
            if (layouts != null)
                layoutTokenization = layouts.getTokenization();
            if (isNotBlank(featuredText)) {
                res = label(featuredText);
            }
        } else
            return null;

        return Pair.of(res, layoutTokenization);
    }

    public Pair<String, List<LayoutToken>> processShort(List<LayoutToken> tokens, Document doc) {
        if (CollectionUtils.isEmpty(tokens))
            return null;

        SortedSet<DocumentPiece> documentParts = new TreeSet<>();

        // we need to identify all the continuous chunks of tokens, and ignore the others
        List<List<LayoutToken>> tokenChunks = new ArrayList<>();
        List<LayoutToken> currentChunk = new ArrayList<>();
        int currentPos = 0;
        for (LayoutToken token : tokens) {
            if (currentChunk.size() != 0) {
                int tokenPos = token.getOffset();
                if (currentPos != tokenPos) {
                    // new chunk
                    tokenChunks.add(currentChunk);
                    currentChunk = new ArrayList<LayoutToken>();
                }
            }
            currentChunk.add(token);
            currentPos = token.getOffset() + token.getText().length();
        }
        // add last chunk
        tokenChunks.add(currentChunk);
        for (List<LayoutToken> chunk : tokenChunks) {
            int endInd = chunk.size() - 1;
            int posStartAbstract = getDocIndexToken(doc, chunk.get(0));
            int posEndAbstract = getDocIndexToken(doc, chunk.get(endInd));
            DocumentPointer dp1 = new DocumentPointer(doc, chunk.get(0).getBlockPtr(), posStartAbstract);
            DocumentPointer dp2 = new DocumentPointer(doc, chunk.get(endInd).getBlockPtr(), posEndAbstract);
            DocumentPiece piece = new DocumentPiece(dp1, dp2);
            documentParts.add(piece);
        }
        Pair<String, LayoutTokenization> featSeg = getBodyTextFeatured(doc, documentParts);
        String res = null;
        List<LayoutToken> layoutTokenization = null;
        if (featSeg != null) {
            String featuredText = featSeg.getLeft();
            LayoutTokenization layouts = featSeg.getRight();
            if (layouts != null)
                layoutTokenization = layouts.getTokenization();
            if ((featuredText != null) && (featuredText.trim().length() > 0)) {
                res = label(featuredText);
            }
        }

        return Pair.of(res, layoutTokenization);
    }

    static public Pair<String, LayoutTokenization> getBodyTextFeatured(Document doc,
                                                                       SortedSet<DocumentPiece> documentBodyParts) {
        if ((documentBodyParts == null) || (documentBodyParts.size() == 0)) {
            return null;
        }
        FeatureFactory featureFactory = FeatureFactory.getInstance();
        StringBuilder fulltext = new StringBuilder();
        String currentFont = null;
        int currentFontSize = -1;

        List<Block> blocks = doc.getBlocks();
        if ((blocks == null) || blocks.size() == 0) {
            return null;
        }

        // vector for features
        FeaturesVectorFullMedicalText features;
        FeaturesVectorFullMedicalText previousFeatures = null;


        boolean endblock;
        boolean endPage = true;
        boolean newPage = true;
        //boolean start = true;
        int mm = 0; // page position
        int nn = 0; // document position
        double lineStartX = Double.NaN;
        boolean indented = false;
        int fulltextLength = 0;
        int pageLength = 0; // length of the current page
        double lowestPos = 0.0;
        double spacingPreviousBlock = 0.0;
        int currentPage = 0;

        List<LayoutToken> layoutTokens = new ArrayList<LayoutToken>();
        fulltextLength = getFullTextLength(doc, documentBodyParts, fulltextLength);

//System.out.println("fulltextLength: " + fulltextLength);

        for (DocumentPiece docPiece : documentBodyParts) {
            DocumentPointer dp1 = docPiece.getLeft();
            DocumentPointer dp2 = docPiece.getRight();

            //int blockPos = dp1.getBlockPtr();
            for (int blockIndex = dp1.getBlockPtr(); blockIndex <= dp2.getBlockPtr(); blockIndex++) {
//System.out.println("blockIndex: " + blockIndex);
                boolean graphicVector = false;
                boolean graphicBitmap = false;
                Block block = blocks.get(blockIndex);
                // length of the page where the current block is
                double pageHeight = block.getPage().getHeight();
                int localPage = block.getPage().getNumber();
                if (localPage != currentPage) {
                    newPage = true;
                    currentPage = localPage;
                    mm = 0;
                    lowestPos = 0.0;
                    spacingPreviousBlock = 0.0;
                }

	            /*if (start) {
	                newPage = true;
	                start = false;
	            }*/

                boolean newline;
                boolean previousNewline = false;
                endblock = false;

	            /*if (endPage) {
	                newPage = true;
	                mm = 0;
					lowestPos = 0.0;
	            }*/

                if (lowestPos > block.getY()) {
                    // we have a vertical shift, which can be due to a change of column or other particular layout formatting
                    spacingPreviousBlock = doc.getMaxBlockSpacing() / 5.0; // default
                } else
                    spacingPreviousBlock = block.getY() - lowestPos;

                String localText = block.getText();
                if (TextUtilities.filterLine(localText)) {
                    continue;
                }
	            /*if (localText != null) {
	                if (localText.contains("@PAGE")) {
	                    mm = 0;
	                    // pageLength = 0;
	                    endPage = true;
	                    newPage = false;
	                } else {
	                    endPage = false;
	                }
	            }*/

                // character density of the block
                double density = 0.0;
                if ((block.getHeight() != 0.0) && (block.getWidth() != 0.0) &&
                    (localText != null) && (!localText.contains("@PAGE")) &&
                    (!localText.contains("@IMAGE")))
                    density = (double) localText.length() / (block.getHeight() * block.getWidth());

                // check if we have a graphical object connected to the current block
                List<GraphicObject> localImages = Document.getConnectedGraphics(block, doc);
                if (localImages != null) {
                    for (GraphicObject localImage : localImages) {
                        if (localImage.getType() == GraphicObjectType.BITMAP)
                            graphicBitmap = true;
                        if (localImage.getType() == GraphicObjectType.VECTOR || localImage.getType() == GraphicObjectType.VECTOR_BOX)
                            graphicVector = true;
                    }
                }

                List<LayoutToken> tokens = block.getTokens();
                if (tokens == null) {
                    continue;
                }

                int n = 0;// token position in current block
                if (blockIndex == dp1.getBlockPtr()) {
//					n = dp1.getTokenDocPos() - block.getStartToken();
                    n = dp1.getTokenBlockPos();
                }
                int lastPos = tokens.size();
                // if it's a last block from a document piece, it may end earlier
                if (blockIndex == dp2.getBlockPtr()) {
                    lastPos = dp2.getTokenBlockPos() + 1;
                    if (lastPos > tokens.size()) {
                        LOGGER.error("DocumentPointer for block " + blockIndex + " points to " +
                            dp2.getTokenBlockPos() + " token, but block token size is " +
                            tokens.size());
                        lastPos = tokens.size();
                    }
                }

                while (n < lastPos) {
                    if (blockIndex == dp2.getBlockPtr()) {
                        //if (n > block.getEndToken()) {
                        if (n > dp2.getTokenDocPos() - block.getStartToken()) {
                            break;
                        }
                    }

                    LayoutToken token = tokens.get(n);
                    layoutTokens.add(token);

                    features = new FeaturesVectorFullMedicalText();
                    features.token = token;

                    double coordinateLineY = token.getY();

                    String text = token.getText();
                    if ((text == null) || (text.length() == 0)) {
                        n++;
                        //mm++;
                        //nn++;
                        continue;
                    }
                    //text = text.replaceAll("\\s+", "");
                    text = text.replace(" ", "");
                    if (text.length() == 0) {
                        n++;
                        mm++;
                        nn++;
                        continue;
                    }

                    if (text.equals("\n")) {
                        newline = true;
                        previousNewline = true;
                        n++;
                        mm++;
                        nn++;
                        continue;
                    } else
                        newline = false;

                    // final sanitisation and filtering
                    text = text.replaceAll("[ \n]", "");
                    if (TextUtilities.filterLine(text)) {
                        n++;
                        continue;
                    }

                    if (previousNewline) {
                        newline = true;
                        previousNewline = false;
                        if ((token != null) && (previousFeatures != null)) {
                            double previousLineStartX = lineStartX;
                            lineStartX = token.getX();
                            double characterWidth = token.width / text.length();
                            if (!Double.isNaN(previousLineStartX)) {
                                if (previousLineStartX - lineStartX > characterWidth)
                                    indented = false;
                                else if (lineStartX - previousLineStartX > characterWidth)
                                    indented = true;
                                // Indentation ends if line start is > 1 character width to the left of previous line start
                                // Indentation starts if line start is > 1 character width to the right of previous line start
                                // Otherwise indentation is unchanged
                            }
                        }
                    }

                    features.string = text;

                    if (graphicBitmap) {
                        features.bitmapAround = true;
                    }
                    if (graphicVector) {
                        features.vectorAround = true;
                    }

                    if (newline) {
                        features.lineStatus = "LINESTART";
                        if (token != null)
                            lineStartX = token.getX();
                        // be sure that previous token is closing a line, except if it's a starting line
                        if (previousFeatures != null) {
                            if (!previousFeatures.lineStatus.equals("LINESTART"))
                                previousFeatures.lineStatus = "LINEEND";
                        }
                    }
                    Matcher m0 = featureFactory.isPunct.matcher(text);
                    if (m0.find()) {
                        features.punctType = "PUNCT";
                    }
                    if (text.equals("(") || text.equals("[")) {
                        features.punctType = "OPENBRACKET";

                    } else if (text.equals(")") || text.equals("]")) {
                        features.punctType = "ENDBRACKET";

                    } else if (text.equals(".")) {
                        features.punctType = "DOT";

                    } else if (text.equals(",")) {
                        features.punctType = "COMMA";

                    } else if (text.equals("-")) {
                        features.punctType = "HYPHEN";

                    } else if (text.equals("\"") || text.equals("\'") || text.equals("`")) {
                        features.punctType = "QUOTE";
                    }

                    if (indented) {
                        features.alignmentStatus = "LINEINDENT";
                    } else {
                        features.alignmentStatus = "ALIGNEDLEFT";
                    }

                    if (n == 0) {
                        features.lineStatus = "LINESTART";
                        // be sure that previous token is closing a line, except if it's a starting line
                        if (previousFeatures != null) {
                            if (!previousFeatures.lineStatus.equals("LINESTART"))
                                previousFeatures.lineStatus = "LINEEND";
                        }
                        if (token != null)
                            lineStartX = token.getX();
                        features.blockStatus = "BLOCKSTART";
                    } else if (n == tokens.size() - 1) {
                        features.lineStatus = "LINEEND";
                        previousNewline = true;
                        features.blockStatus = "BLOCKEND";
                        endblock = true;
                    } else {
                        // look ahead...
                        boolean endline = false;

                        int ii = 1;
                        boolean endloop = false;
                        while ((n + ii < tokens.size()) && (!endloop)) {
                            LayoutToken tok = tokens.get(n + ii);
                            if (tok != null) {
                                String toto = tok.getText();
                                if (toto != null) {
                                    if (toto.equals("\n")) {
                                        endline = true;
                                        endloop = true;
                                    } else {
                                        if ((toto.length() != 0)
                                            && (!(toto.startsWith("@IMAGE")))
                                            && (!(toto.startsWith("@PAGE")))
                                            && (!text.contains(".pbm"))
                                            && (!text.contains(".svg"))
                                            && (!text.contains(".png"))
                                            && (!text.contains(".jpg"))) {
                                            endloop = true;
                                        }
                                    }
                                }
                            }

                            if (n + ii == tokens.size() - 1) {
                                endblock = true;
                                endline = true;
                            }

                            ii++;
                        }

                        if ((!endline) && !(newline)) {
                            features.lineStatus = "LINEIN";
                        } else if (!newline) {
                            features.lineStatus = "LINEEND";
                            previousNewline = true;
                        }

                        if ((!endblock) && (features.blockStatus == null))
                            features.blockStatus = "BLOCKIN";
                        else if (features.blockStatus == null) {
                            features.blockStatus = "BLOCKEND";
                            //endblock = true;
                        }
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

                    if (featureFactory.test_digit(text)) {
                        features.digit = "CONTAINSDIGITS";
                    }

                    Matcher m = featureFactory.isDigit.matcher(text);
                    if (m.find()) {
                        features.digit = "ALLDIGIT";
                    }

                    if (currentFont == null) {
                        currentFont = token.getFont();
                        features.fontStatus = "NEWFONT";
                    } else if (!currentFont.equals(token.getFont())) {
                        currentFont = token.getFont();
                        features.fontStatus = "NEWFONT";
                    } else
                        features.fontStatus = "SAMEFONT";

                    int newFontSize = (int) token.getFontSize();
                    if (currentFontSize == -1) {
                        currentFontSize = newFontSize;
                        features.fontSize = "HIGHERFONT";
                    } else if (currentFontSize == newFontSize) {
                        features.fontSize = "SAMEFONTSIZE";
                    } else if (currentFontSize < newFontSize) {
                        features.fontSize = "HIGHERFONT";
                        currentFontSize = newFontSize;
                    } else if (currentFontSize > newFontSize) {
                        features.fontSize = "LOWERFONT";
                        currentFontSize = newFontSize;
                    }

                    if (token.isBold())
                        features.bold = true;

                    if (token.isItalic())
                        features.italic = true;

                    if (features.capitalisation == null)
                        features.capitalisation = "NOCAPS";

                    if (features.digit == null)
                        features.digit = "NODIGIT";

                    if (features.punctType == null)
                        features.punctType = "NOPUNCT";

                    features.relativeDocumentPosition = featureFactory
                        .linearScaling(nn, fulltextLength, NBBINS_POSITION);
                    // System.out.println(mm + " / " + pageLength);
                    features.relativePagePositionChar = featureFactory
                        .linearScaling(mm, pageLength, NBBINS_POSITION);

                    int pagePos = featureFactory
                        .linearScaling(coordinateLineY, pageHeight, NBBINS_POSITION);
                    if (pagePos > NBBINS_POSITION)
                        pagePos = NBBINS_POSITION;
                    features.relativePagePosition = pagePos;
//System.out.println((coordinateLineY) + " " + (pageHeight) + " " + NBBINS_POSITION + " " + pagePos);

                    if (spacingPreviousBlock != 0.0) {
                        features.spacingWithPreviousBlock = featureFactory
                            .linearScaling(spacingPreviousBlock - doc.getMinBlockSpacing(),
                                doc.getMaxBlockSpacing() - doc.getMinBlockSpacing(), NBBINS_SPACE);
                    }

                    if (density != -1.0) {
                        features.characterDensity = featureFactory
                            .linearScaling(density - doc.getMinCharacterDensity(), doc.getMaxCharacterDensity() - doc.getMinCharacterDensity(), NBBINS_DENSITY);
//System.out.println((density-doc.getMinCharacterDensity()) + " " + (doc.getMaxCharacterDensity()-doc.getMinCharacterDensity()) + " " + NBBINS_DENSITY + " " + features.characterDensity);
                    }

                    if (token.isSuperscript()) {
                        features.superscript = true;
                    }

                    // fulltext.append(features.printVector());
                    if (previousFeatures != null) {
                        if (features.blockStatus.equals("BLOCKSTART") &&
                            previousFeatures.blockStatus.equals("BLOCKIN")) {
                            // this is a post-correction due to the fact that the last character of a block
                            // can be a space or EOL character
                            previousFeatures.blockStatus = "BLOCKEND";
                            previousFeatures.lineStatus = "LINEEND";
                        }
                        fulltext.append(previousFeatures.printVector());
                    }

                    n++;
                    mm += text.length();
                    nn += text.length();
                    previousFeatures = features;
                }
                // lowest position of the block
                lowestPos = block.getY() + block.getHeight();

                //blockPos++;
            }
        }
        if (previousFeatures != null) {
            fulltext.append(previousFeatures.printVector());

        }

        return Pair.of(fulltext.toString(),
            new LayoutTokenization(layoutTokens));
    }

    /**
     * Evaluate the length of the fulltext
     */
    private static int getFullTextLength(Document doc, SortedSet<DocumentPiece> documentBodyParts, int fulltextLength) {
        for (DocumentPiece docPiece : documentBodyParts) {
            DocumentPointer dp1 = docPiece.getLeft();
            DocumentPointer dp2 = docPiece.getRight();

            int tokenStart = dp1.getTokenDocPos();
            int tokenEnd = dp2.getTokenDocPos();
            for (int i = tokenStart; i <= tokenEnd && i < doc.getTokenizations().size(); i++) {
                //tokenizationsBody.add(tokenizations.get(i));
                fulltextLength += doc.getTokenizations().get(i).getText().length();
            }
        }
        return fulltextLength;
    }

    /**
     * Return the index of a token in a document tokenization
     */
    private static int getDocIndexToken(Document doc, LayoutToken token) {
        int blockPtr = token.getBlockPtr();
        Block block = doc.getBlocks().get(blockPtr);
        int startTokenBlockPos = block.getStartToken();
        List<LayoutToken> tokens = doc.getTokenizations();
        int i = startTokenBlockPos;
        for (; i < tokens.size(); i++) {
            int offset = tokens.get(i).getOffset();
            if (offset >= token.getOffset())
                break;
        }
        return i;
    }

    /**
     * Process all the PDF in a given directory with a header medical process and
     * produce the corresponding training data format files for manual
     * correction. The goal of this method is to help to produce additional
     * traning data based on an existing model.
     *
     * @param inputDirectory  - the path to the directory containing PDF to be processed.
     * @param outputDirectory - the path to the directory where the results as XML files
     *                        and CRF feature files shall be written.
     * @param ind             - identifier integer to be included in the resulting files to
     *                        identify the training case. This is optional: no identifier
     *                        will be included if ind = -1
     * @return the number of processed files.
     */

    public int createTrainingFullMedicalTextBatch(String inputDirectory,
                                                  String outputDirectory,
                                                  boolean blank,
                                                  int ind) throws IOException {
        try {
            File path = new File(inputDirectory);
            if (!path.exists()) {
                throw new GrobidException("Cannot create training data because input directory can not be accessed: " + inputDirectory);
            }

            File pathOut = new File(outputDirectory);
            if (!pathOut.exists()) {
                throw new GrobidException("Cannot create training data because output directory can not be accessed: " + outputDirectory);
            }

            // we process all pdf files in the directory
            File[] refFiles = path.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    System.out.println(name);
                    return name.endsWith(".pdf") || name.endsWith(".PDF");
                }
            });

            if (refFiles == null)
                return 0;

            System.out.println(refFiles.length + " files to be processed.");

            int n = 0;
            if (ind == -1) {
                // for undefined identifier (value at -1), we initialize it to 0
                n = 1;
            }
            for (final File file : refFiles) {
                try {
                    if (blank) {
                        // create blank training data for the full-medical-text model
                        createBlankTrainingFromPDF(file, outputDirectory, n);
                    } else {
                        // create pre-annotated training data based on existing full-medical-text segmentation model
                        createTraining(file, outputDirectory, n);
                    }
                } catch (final Exception exp) {
                    LOGGER.error("An error occurred while processing the following pdf: "
                        + file.getPath() + ": " + exp);
                }
                if (ind != -1)
                    n++;
            }

            return refFiles.length;
        } catch (final Exception exp) {
            throw new GrobidException("An exception occurred while running Grobid batch.", exp);
        }
    }

    /**
     * Process the specified pdf and format the result as training data for all the models.
     *
     * @param inputFile  input file
     * @param pathOutput path for result
     * @param id         id
     */
    public Document createTraining(File inputFile,
                                   String pathOutput,
                                   int id) {
        if (tmpPath == null)
            throw new GrobidResourceException("Cannot process pdf file, because temp path is null.");
        if (!tmpPath.exists()) {
            throw new GrobidResourceException("Cannot process pdf file, because temp path '" +
                tmpPath.getAbsolutePath() + "' does not exists.");
        }
        DocumentSource documentSource = null;
        try {
            if (!inputFile.exists()) {
                throw new GrobidResourceException("Cannot train for full-medical-text, because the file '" +
                    inputFile.getAbsolutePath() + "' does not exists.");
            }
            String pdfFileName = inputFile.getName();
            Writer writer = null;

            // path for medical report segmenter model
            File outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.medical.tei.xml"));
            File outputTextFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.medical.rawtxt"));
            File outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.medical"));

            // 1. MEDICAL REPORT SEGMENTER MODEL
            documentSource = DocumentSource.fromPdf(inputFile, -1, -1, false, true, true);
            Document doc = new Document(documentSource);
            doc.addTokenizedDocument(GrobidAnalysisConfig.defaultInstance());

            if (doc.getBlocks() == null) {
                throw new Exception("PDF parsing resulted in empty content");
            }
            doc.produceStatistics();

            // 1. SEGMENTATION MODEL
            String featuredData = parsers.getMedicalReportSegmenterParser().getAllLinesFeatured(doc);
            List<LayoutToken> tokenizations = doc.getTokenizations();

            // we write first the full text untagged (but featurized with segmentation features)
            writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
            writer.write(featuredData + "\n");
            writer.close();

            // also write the raw text as seen before segmentation
            StringBuffer rawtxt = new StringBuffer();
            for (LayoutToken txtline : tokenizations) {
                rawtxt.append(txtline.getText());
            }
            //write the text to the file
            FileUtils.writeStringToFile(outputTextFile, rawtxt.toString(), StandardCharsets.UTF_8);

            // lastly, write the featurized and tagged data
            if (isNotBlank(featuredData)) {
                String rese = parsers.getMedicalReportSegmenterParser().label(featuredData);
                StringBuffer bufferFulltext = parsers.getMedicalReportSegmenterParser().trainingExtraction(rese, tokenizations, doc);

                // write the TEI file to reflect the extact layout of the text as extracted from the pdf
                writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                writer.write("<?xml version=\"1.0\" ?>\n<tei xml:space=\"preserve\">\n\t<teiHeader>\n\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") +
                    "\"/>\n\t</teiHeader>\n\t<text xml:lang=\"fr\">\n");

                writer.write(bufferFulltext.toString());
                writer.write("\n\t</text>\n</tei>\n");
                writer.close();
            }

            // Now we process and create training data for other models ...

            // But first, call the medical-report-segmenter model to have high level segmentation
            doc = parsers.getMedicalReportSegmenterParser().processing(documentSource,
                GrobidAnalysisConfig.defaultInstance());
            List<LayoutToken> tokenizationsFull = doc.getTokenizations();

            // 2. HEADER MEDICAL REPORT  MODEL

            // path for header medical report model
            outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.header.medical.tei.xml"));
            outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.header.medical"));

            SortedSet<DocumentPiece> documentHeaderParts = doc.getDocumentPart(MedicalLabels.HEADER);
            if (documentHeaderParts != null) {
                /*List<LayoutToken> headerTokenizations = new ArrayList<LayoutToken>();
                for (DocumentPiece docPiece : documentHeaderParts) {
                    DocumentPointer dp1 = docPiece.getLeft();
                    DocumentPointer dp2 = docPiece.getRight();

                    int tokens = dp1.getTokenDocPos();
                    int tokene = dp2.getTokenDocPos();
                    for (int i = tokens; i < tokene; i++) {
                        headerTokenizations.add(tokenizationsFull.get(i));
                    }
                }*/
                Pair<String, List<LayoutToken>> featuredHeader = parsers.getHeaderMedicalParser().getSectionHeaderFeatured(doc, documentHeaderParts);
                String header = featuredHeader.getLeft();
                List<LayoutToken> headerTokenizations = featuredHeader.getRight();
                if ((header != null) && (header.trim().length() > 0)) {
                    // we write the featurized header data
                    writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                    writer.write(header + "\n");
                    writer.close();

                    // featured and labeled (tagged) data
                    String labeledHeader = parsers.getHeaderMedicalParser().label(header);

                    // buffer for the header block
                    StringBuilder bufferHeader = parsers.getHeaderMedicalParser().trainingExtraction(labeledHeader, headerTokenizations);

                    // write the training TEI file for header which reflects the extract layout of the text as
                    // extracted from the pdf
                    writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                    writer.write("<?xml version=\"1.0\" ?>\n<tei xml:space=\"preserve\">\n\t<teiHeader>\n\t\t<fileDesc xml:id=\""
                        + pdfFileName.replace(".pdf", "")
                        + "\"/>\n\t</teiHeader>\n\t<text");

                    writer.write(" xml:lang=\"fr\"");
                    writer.write(">\n\t\t<front>\n");

                    writer.write(bufferHeader.toString());
                    writer.write("\n\t\t</front>\n\t</text>\n</tei>\n");
                    writer.close();

                    // buffer for the affiliation+address block
                    /*StringBuilder bufferAffiliation =
                        parsers.getAffiliationAddressParser().trainingExtraction(rese, headerTokenizations);*/

                    // 3. DATELINE MODEL
                    // path for dateline  model
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.dateline.tei.xml"));
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.dateline"));

                    // buffer for the dateline block
                    StringBuilder bufferDateline = null;
                    // we need to rebuild the found date string as it appears
                    String input = "";
                    int q = 0;
                    // get the results of the header model and collect only those that are labeled as "<dateline>"
                    StringTokenizer st = new StringTokenizer(labeledHeader, "\n");
                    while (st.hasMoreTokens() && (q < headerTokenizations.size())) {
                        String line = st.nextToken();
                        String theTotalTok = headerTokenizations.get(q).getText();
                        String theTok = headerTokenizations.get(q).getText();
                        while (theTok.equals(" ") || theTok.equals("\t") || theTok.equals("\n") || theTok.equals("\r")) {
                            q++;
                            if ((q > 0) && (q < headerTokenizations.size())) {
                                theTok = headerTokenizations.get(q).getText();
                                theTotalTok += theTok;
                            }
                        }
                        if (line.endsWith("<dateline>")) {
                            input += theTotalTok;
                        }
                        q++;
                    }
                    List<String> inputs = new ArrayList<String>();
                    if (input != null && input.trim().length() > 0) {
                        inputs.add(input.trim());
                        bufferDateline = parsers.getDatelineParser().trainingExtraction(inputs); //if the models exists already

                        List<String> tokenizationDateline = analyzer.tokenize(input);
                        List<String> datelineBlocks = new ArrayList<String>();
                        if (tokenizationDateline.size() == 0)
                            return null;
                        for (String tok : tokenizationDateline) {
                            if (tok.equals("\n")) {
                                datelineBlocks.add("@newline");
                            } else if (!tok.equals(" ")) {
                                datelineBlocks.add(tok + " <dateline>");
                            }
                        }

                        // we write the featured dateline, but first, take the tokens from the dateline part
                        tokenizations = analyzer.tokenizeWithLayoutToken(input);
                        if (tokenizations.size() == 0)
                            return null;
                        List<OffsetPosition> placeNamePositions = lexicon.tokenPositionsLocationNames(tokenizations);

                        String featuredDateline = FeaturesVectorDateline.addFeaturesDateline(tokenizations,
                            null, placeNamePositions);

                        if (featuredDateline != null) {
                            writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                            writer.write(featuredDateline + "\n");
                            writer.close();
                        }

                        if (bufferDateline != null) {
                            if (bufferDateline.length() > 0) {
                                writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                                writer.write("<tei xml:space=\"preserve\">\n");
                                writer.write("\t<teiHeader>\n");
                                writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                                writer.write("\t\t\t<datelines>\n");
                                writer.write("\t\t\t" + bufferDateline.toString());
                                writer.write("\t\t\t</datelines>\n");
                                writer.write("\t\t</fileDesc>\n");
                                writer.write("\t</teiHeader>\n");
                                writer.write("</tei>");
                                writer.close();
                            }
                        }
                    }

                    // 4. MEDIC MODEL (from header information)
                    // path for medic  model
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.header.medic.tei.xml"));
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.header.medic"));

                    // buffer for the medics block
                    StringBuilder bufferMedic = null;
                    // we need to rebuild the found string as it appears
                    input = "";
                    q = 0;
                    st = new StringTokenizer(labeledHeader, "\n");
                    while (st.hasMoreTokens() && (q < headerTokenizations.size())) {
                        String line = st.nextToken();
                        String theTotalTok = headerTokenizations.get(q).getText();
                        String theTok = headerTokenizations.get(q).getText();
                        while (theTok.equals(" ") || theTok.equals("\t") || theTok.equals("\n") || theTok.equals("\r")) {
                            q++;
                            if ((q > 0) && (q < headerTokenizations.size())) {
                                theTok = headerTokenizations.get(q).getText();
                                theTotalTok += theTok;
                            }
                        }
                        if (line.endsWith("<medic>")) {
                            input += theTotalTok;
                        }
                        q++;
                    }

                    inputs = new ArrayList<String>();
                    if (input != null && input.trim().length() > 0) {
                        inputs.add(input.trim());
                        bufferMedic = parsers.getMedicParser().trainingExtraction(inputs); //if the models exists already

                        // force analyser with English, to avoid bad surprise
                        List<LayoutToken> tokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(input, new Language("en", 1.0));
                        List<String> tokenizationMedic = analyzer.tokenize(input);
                        List<String> medicBlocks = new ArrayList<String>();
                        if (tokenizationMedic.size() == 0)
                            return null;
                        for (String tok : tokenizationMedic) {
                            if (tok.equals("\n")) {
                                medicBlocks.add("@newline");
                            } else if (!tok.equals(" ")) {
                                medicBlocks.add(tok + " <medic>");
                            }
                        }

                        List<OffsetPosition> locationPositions = lexicon.tokenPositionsLocationNames(tokens);
                        List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(tokens);
                        List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(tokens);
                        List<OffsetPosition> emailPositions = lexicon.tokenPositionsEmailPattern(tokens);
                        List<OffsetPosition> urlPositions = lexicon.tokenPositionsUrlPattern(tokens);
                        // we write the medic data with features
                        String featuredMedic = FeaturesVectorMedic.addFeaturesMedic(tokens, null,
                            locationPositions, titlePositions, suffixPositions, emailPositions, urlPositions);

                        if (featuredMedic != null) {
                            writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                            writer.write(featuredMedic + "\n");
                            writer.close();
                        }

                        if ((bufferMedic != null) && (bufferMedic.length() > 0)) {
                            writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                            writer.write("<tei xml:space=\"preserve\">\n");
                            writer.write("\t<teiHeader>\n");
                            writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                            writer.write("\t\t\t<medics>\n");
                            writer.write("\t\t\t\t<medic>\n");
                            writer.write("\t\t\t\t\t" + bufferMedic.toString());
                            writer.write("\t\t\t\t</medic>\n");
                            writer.write("\t\t\t</medics>\n");
                            writer.write("\t\t</fileDesc>\n");
                            writer.write("\t</teiHeader>\n");
                            writer.write("</tei>");
                            writer.close();
                        }
                    }

                    // 5a. PERSON NAME MODEL (from medics)
                    // path for person name model
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.medic.name.tei.xml"));
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.medic.name"));

                    if (input != null && input.trim().length() > 0) {
                        List<Medic> listMedics = parsers.getMedicParser().processing(input); //if the medic model exists already

                        // if the model doesn't exist, just write the text to file
                        if (listMedics != null && listMedics.size() > 0) {
                            for (Medic medic : listMedics) {
                                if (medic.getPersName() != null) { // take only the names
                                    // write the labeled data
                                    writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                                    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                                    writer.write("<tei xml:space=\"preserve\">\n");
                                    writer.write("\t<teiHeader>\n");
                                    writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                                    writer.write("\t\t\t<medics>\n");
                                    writer.write("\t\t\t\t<name>\n");
                                    writer.write(medic.getPersName() + "\n"); // unlabelled data
                                    writer.write("\t\t\t\t</name>\n");
                                    writer.write("\t\t\t</medics>\n");
                                    writer.write("\t\t</fileDesc>\n");
                                    writer.write("\t</teiHeader>\n");
                                    writer.write("</tei>");
                                    writer.close();
                                    List<LayoutToken> nameTokenizations = analyzer.tokenizeWithLayoutToken(medic.getPersName());

                                    List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(nameTokenizations);
                                    List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(nameTokenizations);

                                    // get the features for the name
                                    String featuredName = FeaturesVectorPersonName.addFeaturesName(nameTokenizations, null,
                                        titlePositions, suffixPositions);

                                    // write the data with features
                                    if (featuredName != null) {
                                        writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                                        writer.write(featuredName + "\n");
                                        writer.close();
                                    }
                                }
                            }
                        }
                    }

                    // 6. PATIENT MODEL
                    // path for patient model
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.patient.tei.xml"));
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.patient"));

                    // buffer for the patients block
                    StringBuilder bufferPatient = null;
                    // we need to rebuild the found string as it appears
                    input = "";
                    q = 0;
                    st = new StringTokenizer(labeledHeader, "\n");
                    while (st.hasMoreTokens() && (q < headerTokenizations.size())) {
                        String line = st.nextToken();
                        String theTotalTok = headerTokenizations.get(q).getText();
                        String theTok = headerTokenizations.get(q).getText();
                        while (theTok.equals(" ") || theTok.equals("\t") || theTok.equals("\n") || theTok.equals("\r")) {
                            q++;
                            if ((q > 0) && (q < headerTokenizations.size())) {
                                theTok = headerTokenizations.get(q).getText();
                                theTotalTok += theTok;
                            }
                        }
                        if (line.endsWith("<patient>")) {
                            input += theTotalTok;
                        }
                        q++;
                    }

                    inputs = new ArrayList<String>();
                    if (input != null && input.trim().length() > 0) {
                        inputs.add(input.trim());
                        bufferPatient = parsers.getPatientParser().trainingExtraction(inputs); //if the models exists already

                        // force analyser with English, to avoid bad surprise
                        List<LayoutToken> tokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(input, new Language("en", 1.0));
                        List<String> tokenizationPatient = analyzer.tokenize(input);
                        List<String> patientBlocks = new ArrayList<String>();
                        if (tokenizationPatient.size() == 0)
                            return null;
                        for (String tok : tokenizationPatient) {
                            if (tok.equals("\n")) {
                                patientBlocks.add("@newline");
                            } else if (!tok.equals(" ")) {
                                patientBlocks.add(tok + " <patient>");
                            }
                        }

                        List<OffsetPosition> locationPositions = lexicon.tokenPositionsLocationNames(tokens);
                        List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(tokens);
                        List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(tokens);
                        // we write the patient data with features
                        String featuredPatient = FeaturesVectorPatient.addFeaturesPatient(tokens, null,
                            locationPositions, titlePositions, suffixPositions);

                        if (featuredPatient != null) {
                            writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                            writer.write(featuredPatient + "\n");
                            writer.close();
                        }

                        if ((bufferPatient != null) && (bufferPatient.length() > 0)) {
                            writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                            writer.write("<tei xml:space=\"preserve\">\n");
                            writer.write("\t<teiHeader>\n");
                            writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                            writer.write("\t\t\t<patients>\n");
                            writer.write("\t\t\t" + bufferPatient.toString());
                            writer.write("\t\t\t</patients>\n");
                            writer.write("\t\t</fileDesc>\n");
                            writer.write("\t</teiHeader>\n");
                            writer.write("</tei>");
                            writer.close();
                        }
                    }

                    // 5b. PERSON NAME MODEL (from patients)
                    // path for person name model
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.patient.name.tei.xml"));
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.patient.name"));

                    if (input != null && input.trim().length() > 0) {
                        List<Patient> listPatients = parsers.getPatientParser().processing(input); //if the patient model exists already

                        // if the model doesn't exist, just write the text to file
                        if (listPatients != null && listPatients.size() > 0) {
                            for (Patient patient : listPatients) {
                                if (patient.getPersName() != null) { // take only the names
                                    // write the labeled data
                                    writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                                    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                                    writer.write("<tei xml:space=\"preserve\">\n");
                                    writer.write("\t<teiHeader>\n");
                                    writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                                    writer.write("\t\t\t<patients>\n");
                                    writer.write("\t\t\t\t<name>\n");
                                    writer.write(patient.getPersName() + "\n"); // unlabelled data
                                    writer.write("\t\t\t\t</name>\n");
                                    writer.write("\t\t\t</patients>\n");
                                    writer.write("\t\t</fileDesc>\n");
                                    writer.write("\t</teiHeader>\n");
                                    writer.write("</tei>");
                                    writer.close();
                                    List<LayoutToken> nameTokenizations = analyzer.tokenizeWithLayoutToken(patient.getPersName());

                                    List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(nameTokenizations);
                                    List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(nameTokenizations);

                                    // get the features for the name
                                    String featuredName = FeaturesVectorPersonName.addFeaturesName(nameTokenizations, null,
                                        titlePositions, suffixPositions);

                                    // write the data with features
                                    if (featuredName != null) {
                                        writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                                        writer.write(featuredName + "\n");
                                        writer.close();
                                    }
                                }
                            }
                        }
                    }
                }
            } // end of the header processing

            // 7. LEFT NOTE MEDICAL REPORT MODEL
            // path for left note model
            outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.medical.tei.xml"));
            outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.medical"));

            // we take the left-note part only
            SortedSet<DocumentPiece> documentLeftNoteParts = doc.getDocumentPart(MedicalLabels.LEFTNOTE);
            tokenizations = doc.getTokenizations(); // tokens of segmentation data

            if (documentLeftNoteParts != null) {
                Pair<String, List<LayoutToken>> featuredLeftNote = parsers.getLeftNoteMedicalParser().getSectionLeftNoteFeatured(doc, documentLeftNoteParts);

                if (featuredLeftNote != null) {
                    String leftNote = featuredLeftNote.getLeft(); // data with features
                    List<LayoutToken> leftNoteTokenizations = featuredLeftNote.getRight(); // tokens information
                    if ((leftNote != null) && (leftNote.trim().length() > 0)) {
                        // we write left-note data with features
                        writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                        writer.write(leftNote + "\n");
                        writer.close();

                        // =============== if the model exists ===============
                        // we tag with the left-note model
                        String rese = parsers.getLeftNoteMedicalParser().label(leftNote);

                        // buffer for the header block, take only the data with the label
                        StringBuilder bufferLeftNote = parsers.getLeftNoteMedicalParser().trainingExtraction(rese, leftNoteTokenizations);

                        // write the training TEI file for header which reflects the extract layout of the text as
                        // extracted from the pdf
                        writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                        writer.write("<?xml version=\"1.0\" ?>\n<tei xml:space=\"preserve\">\n\t<teiHeader>\n\t\t<fileDesc xml:id=\""
                            + pdfFileName.replace(".pdf", "")
                            + "\"/>\n\t</teiHeader>\n\t<text");

                        writer.write(" xml:lang=\"fr\"");
                        writer.write(">\n\t\t<listOrg>\n");

                        writer.write(bufferLeftNote.toString());
                        writer.write("\n\t\t</listOrg>\n\t</text>\n</tei>\n");
                        writer.close();

                        // 4. MEDIC MODEL (from left note information)
                        // path for medic model
                        outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.medic.tei.xml"));
                        outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.medic"));

                        // buffer for the medics block
                        StringBuilder bufferMedic = null;
                        // we need to rebuild the found string as it appears
                        String input = "";
                        int q = 0;
                        StringTokenizer st = new StringTokenizer(rese, "\n");
                        while (st.hasMoreTokens() && (q < leftNoteTokenizations.size())) {
                            String line = st.nextToken();
                            String theTotalTok = leftNoteTokenizations.get(q).getText();
                            String theTok = leftNoteTokenizations.get(q).getText();
                            while (theTok.equals(" ") || theTok.equals("\t") || theTok.equals("\n") || theTok.equals("\r")) {
                                q++;
                                if ((q > 0) && (q < leftNoteTokenizations.size())) {
                                    theTok = leftNoteTokenizations.get(q).getText();
                                    theTotalTok += theTok;
                                }
                            }
                            if (line.endsWith("<medic>")) {
                                input += theTotalTok;
                            }
                            q++;
                        }

                        List<String> inputs = new ArrayList<String>();
                        if (input != null && input.trim().length() > 0) {
                            inputs.add(input.trim());
                            bufferMedic = parsers.getMedicParser().trainingExtraction(inputs); //if the models exists already

                            // force analyser with English, to avoid bad surprise
                            List<LayoutToken> tokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(input, new Language("en", 1.0));
                            List<String> tokenizationMedic = analyzer.tokenize(input);
                            List<String> medicBlocks = new ArrayList<String>();
                            if (tokenizationMedic.size() == 0)
                                return null;
                            for (String tok : tokenizationMedic) {
                                if (tok.equals("\n")) {
                                    medicBlocks.add("@newline");
                                } else if (!tok.equals(" ")) {
                                    medicBlocks.add(tok + " <medic>");
                                }
                            }

                            List<OffsetPosition> locationPositions = lexicon.tokenPositionsLocationNames(tokens);
                            List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(tokens);
                            List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(tokens);
                            List<OffsetPosition> emailPositions = lexicon.tokenPositionsEmailPattern(tokens);
                            List<OffsetPosition> urlPositions = lexicon.tokenPositionsUrlPattern(tokens);
                            // we write the medic data with features
                            String featuredMedic = FeaturesVectorMedic.addFeaturesMedic(tokens, null,
                                locationPositions, titlePositions, suffixPositions, emailPositions, urlPositions);

                            if (featuredMedic != null) {
                                writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                                writer.write(featuredMedic + "\n");
                                writer.close();
                            }


                            if ((bufferMedic != null) && (bufferMedic.length() > 0)) {
                                writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                                writer.write("<tei xml:space=\"preserve\">\n");
                                writer.write("\t<teiHeader>\n");
                                writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                                writer.write("\t\t\t<medics>\n");
                                writer.write("\t\t\t\t<medic>\n");
                                writer.write("\t\t\t\t\t" + bufferMedic.toString());
                                writer.write("\t\t\t\t</medic>\n");
                                writer.write("\t\t\t</medics>\n");
                                writer.write("\t\t</fileDesc>\n");
                                writer.write("\t</teiHeader>\n");
                                writer.write("</tei>");
                                writer.close();
                            }
                        }

                        // 5. ORGANIZATION MODEL (from left note information)
                        // path for medic model
                        outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.organization.tei.xml"));
                        outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.organization"));

                        // buffer for the medics block
                        StringBuilder bufferOrg = null;
                        // we need to rebuild the found string as it appears
                        input = "";
                        q = 0;
                        st = new StringTokenizer(rese, "\n");
                        while (st.hasMoreTokens() && (q < leftNoteTokenizations.size())) {
                            String line = st.nextToken();
                            String theTotalTok = leftNoteTokenizations.get(q).getText();
                            String theTok = leftNoteTokenizations.get(q).getText();
                            while (theTok.equals(" ") || theTok.equals("\t") || theTok.equals("\n") || theTok.equals("\r")) {
                                q++;
                                if ((q > 0) && (q < leftNoteTokenizations.size())) {
                                    theTok = leftNoteTokenizations.get(q).getText();
                                    theTotalTok += theTok;
                                }
                            }
                            if (line.endsWith("<ghu>") || line.endsWith("<chu>") || line.endsWith("<dmu>") ||
                                line.endsWith("<pole>") || line.endsWith("<institution>") || line.endsWith("<university>") ||
                                line.endsWith("<site>") || line.endsWith("<hospital>") || line.endsWith("<center>") ||
                                line.endsWith("<service>") || line.endsWith("<department>") || line.endsWith("<unit>") ||
                                line.endsWith("<sub>") || line.endsWith("<organization>")) {
                                input += theTotalTok;
                            }
                            q++;
                        }

                        inputs = new ArrayList<String>();
                        if (input != null && input.trim().length() > 0) {
                            inputs.add(input.trim());
                            bufferOrg = parsers.getOrganizationParser().trainingExtraction(inputs); //if the models exists already

                            // force analyser with English, to avoid bad surprise
                            List<LayoutToken> tokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(input, new Language("en", 1.0));
                            List<String> tokenizationOrg = analyzer.tokenize(input);
                            List<String> orgBlocks = new ArrayList<String>();
                            if (tokenizationOrg.size() == 0)
                                return null;
                            for (String tok : tokenizationOrg) {
                                if (tok.equals("\n")) {
                                    orgBlocks.add("@newline");
                                } else if (!tok.equals(" ")) {
                                    orgBlocks.add(tok + " <org>");
                                }
                            }

                            List<OffsetPosition> locationPositions = lexicon.tokenPositionsLocationNames(tokens);
                            List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(tokens);
                            List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(tokens);
                            List<OffsetPosition> emailPositions = lexicon.tokenPositionsEmailPattern(tokens);
                            List<OffsetPosition> urlPositions = lexicon.tokenPositionsUrlPattern(tokens);
                            // we write the medic data with features
                            String featuredOrg = FeaturesVectorOrganization.addFeaturesOrganization(tokens, null,
                                locationPositions, titlePositions, suffixPositions, emailPositions, urlPositions);

                            if (featuredOrg != null) {
                                writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                                writer.write(featuredOrg + "\n");
                                writer.close();
                            }


                            if ((bufferOrg != null) && (bufferOrg.length() > 0)) {
                                writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                                writer.write("<tei xml:space=\"preserve\">\n");
                                writer.write("\t<teiHeader>\n");
                                writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                                writer.write("\t\t\t<listOrg>\n");
                                writer.write("\t\t\t\t<org>\n");
                                writer.write("\t\t\t\t\t" + bufferOrg.toString());
                                writer.write("\t\t\t\t</org>\n");
                                writer.write("\t\t\t</listOrg>\n");
                                writer.write("\t\t</fileDesc>\n");
                                writer.write("\t</teiHeader>\n");
                                writer.write("</tei>");
                                writer.close();
                            }
                        }
                    }
                }
            }

            // 8. FULL MEDICAL TEXT MODEL
            // we take the body part only
            SortedSet<DocumentPiece> documentBodyParts = doc.getDocumentPart(MedicalLabels.BODY);
            if (documentBodyParts != null) {
                Pair<String, LayoutTokenization> featSeg = getBodyTextFeatured(doc, documentBodyParts);
                if (featSeg != null) {
                    // if no textual body part found, nothing to generate
                    String bodytext = featSeg.getLeft();
                    List<LayoutToken> tokenizationsBody = featSeg.getRight().getTokenization();

                    // we write the full text untagged
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.full.medical.text"));
                    writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                    writer.write(bodytext + "\n");
                    writer.close();

                    String rese = label(bodytext);
                    StringBuilder bufferFulltext = trainingExtraction(rese, tokenizationsBody);

                    // write the TEI file to reflect the extract layout of the text as extracted from the pdf
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.full.medical.text.tei.xml"));
                    writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                    if (id == -1) {
                        writer.write("<?xml version=\"1.0\" ?>\n<tei xml:space=\"preserve\">\n\t<teiHeader/>\n\t<text xml:lang=\"fr\">\n");
                    } else {
                        writer.write("<?xml version=\"1.0\" ?>\n<tei xml:space=\"preserve\">\n\t<teiHeader>\n\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") +
                            "\"/>\n\t</teiHeader>\n\t<text xml:lang=\"fr\">\n");
                    }
                    writer.write(bufferFulltext.toString());
                    writer.write("\n\t</text>\n</tei>\n");
                    writer.close();
                }
            }

            return doc;

        } catch (Exception e) {
            throw new GrobidException("An exception occurred while running Grobid training" +
                " data generation for full text.", e);
        } finally {
            DocumentSource.close(documentSource, true, true, true);
        }
    }

    /**
     * Process the specified pdf and format the result as blank training data for the header model.
     *
     * @param inputFile  input PDF file
     * @param pathOutput path to raw monograph featured sequence
     * @param pathOutput path to TEI
     * @param id         id
     */

    public Document createBlankTrainingFromPDF(File inputFile,
                                               String pathOutput,
                                               int id) {
        if (tmpPath == null)
            throw new GrobidResourceException("Cannot process pdf file, because temp path is null.");
        if (!tmpPath.exists()) {
            throw new GrobidResourceException("Cannot process pdf file, because temp path '" +
                tmpPath.getAbsolutePath() + "' does not exists.");
        }
        DocumentSource documentSource = null;
        Document doc = null;
        GrobidAnalysisConfig config = null;
        try {
            config = GrobidAnalysisConfig.defaultInstance();
            if (!inputFile.exists()) {
                throw new GrobidResourceException("Cannot train for full-medical-text, because the file '" +
                    inputFile.getAbsolutePath() + "' does not exists.");
            }
            String pdfFileName = inputFile.getName();
            Writer writer = null;

            // path for medical report segmenter model
            File outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.medical.blank.tei.xml"));
            File outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.medical"));

            // 1. MEDICAL REPORT SEGMENTER MODEL
            documentSource = DocumentSource.fromPdf(inputFile, -1, -1, false, true, true);
            doc = new Document(documentSource);
            doc.addTokenizedDocument(config);

            if (doc.getBlocks() == null) {
                throw new Exception("PDF parsing resulted in empty content");
            }
            doc.produceStatistics();

            // create training data for the medical report segmenter model
            String featuredData = parsers.getMedicalReportSegmenterParser().getAllLinesFeatured(doc);
            List<LayoutToken> tokenizationsFull = doc.getTokenizations();

            // we write first the featurized segmentation data)
            writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
            writer.write(featuredData + "\n");
            writer.close();

            // also write the raw text as seen before segmentation
            StringBuffer rawtxt = new StringBuffer();
            for (LayoutToken txtline : tokenizationsFull) {
                rawtxt.append(TextUtilities.HTMLEncode(txtline.getText()));
            }

            // lastly, write the featurized yet unlabeled data (for the annotation process)
            if (isNotBlank(featuredData) && isNotBlank(rawtxt)) {
                // write the TEI file to reflect the extact layout of the text as extracted from the pdf
                writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                writer.write("<?xml version=\"1.0\" ?>\n<tei xml:space=\"preserve\">\n\t<teiHeader>\n\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") +
                    "\"/>\n\t</teiHeader>\n\t<text xml:lang=\"fr\">\n");

                writer.write(rawtxt.toString());
                writer.write("\n\t</text>\n</tei>\n");
                writer.close();
            }

            // Now we process and create training data for other models ...
            // 2. HEADER MEDICAL REPORT  MODEL
            // path for header medical report model
            outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.header.medical.blank.tei.xml"));
            outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.header.medical"));

            // segment first with medical report segmenter model (yes, it's assumed that the segmentation model exists from the previous process
            doc = parsers.getMedicalReportSegmenterParser().processing(documentSource, config);
            // take only the header part
            SortedSet<DocumentPiece> documentHeaderParts = doc.getDocumentPart(MedicalLabels.HEADER);
            String header = null;
            List<LayoutToken> headerTokenization = new ArrayList<>();
            if (documentHeaderParts != null) {
                Pair<String, List<LayoutToken>> featuredHeader = parsers.getHeaderMedicalParser().getSectionHeaderFeatured(doc, documentHeaderParts);
                // get the features (left) and the tokenizations (right)
                header = featuredHeader.getLeft();
                headerTokenization = featuredHeader.getRight();
                if ((header != null) && (header.trim().length() > 0)) {
                    // we write the header untagged (but featurized)
                    writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                    writer.write(header + "\n");
                    writer.close();

                    // write the unlabeled training TEI file for the header
                    writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                    writer.write("<?xml version=\"1.0\" ?>\n<tei xml:space=\"preserve\">\n\t<teiHeader>\n\t\t<fileDesc xml:id=\""
                        + pdfFileName.replace(".pdf", "")
                        + "\"/>\n\t</teiHeader>\n\t<text xml:lang=\"fr\">\n");
                    writer.write("\t\t<front>\n");
                    StringBuilder bufferHeader = new StringBuilder();

                    for (LayoutToken token : headerTokenization) {
                        bufferHeader.append(token.getText());
                    }

                    writer.write(bufferHeader.toString());
                    writer.write("\t\t</front>\n\t</text>\n</tei>\n");
                    writer.close();

                    // we then must assume that from the previous process, the header model exists
                    String rese = parsers.getHeaderMedicalParser().label(header);

                    // 3. DATELINE MODEL
                    // path for dateline  model
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.dateline.blank.tei.xml"));
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.dateline"));

                    // get the results of the header model and collect only those that are labeled as "<dateline>"
                    // we need to rebuild the found date string as it appears
                    String input = "";
                    int q = 0;
                    // get the results of the header model and collect only those that are labeled as "<dateline>"
                    StringTokenizer st = new StringTokenizer(rese, "\n");
                    while (st.hasMoreTokens() && (q < headerTokenization.size())) {
                        String line = st.nextToken();
                        String theTotalTok = headerTokenization.get(q).getText();
                        String theTok = headerTokenization.get(q).getText();
                        while (theTok.equals(" ") || theTok.equals("\t") || theTok.equals("\n") || theTok.equals("\r")) {
                            q++;
                            if ((q > 0) && (q < headerTokenization.size())) {
                                theTok = headerTokenization.get(q).getText();
                                theTotalTok += theTok;
                            }
                        }
                        if (line.endsWith("<dateline>")) {
                            input += theTotalTok;
                        }
                        q++;
                    }

                    if ((input != null) && (input.length() > 0)) {
                        List<String> tokenizationDateline = analyzer.tokenize(input);
                        if (tokenizationDateline.size() == 0)
                            return null;

                        // take the tokens information of the dateline part
                        List<LayoutToken> tokenizationsDateline = analyzer.tokenizeWithLayoutToken(input);
                        if (tokenizationsDateline.size() == 0)
                            return null;
                        List<OffsetPosition> placeNamePositions = lexicon.tokenPositionsLocationNames(tokenizationsDateline);

                        String featuredDateline = FeaturesVectorDateline.addFeaturesDateline(tokenizationsDateline,
                            null, placeNamePositions);

                        // we write the featured dateline
                        if (featuredDateline != null) {
                            writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                            writer.write(featuredDateline + "\n");
                            writer.close();
                        }

                        // we write the dateline data yet unlabeled
                        writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                        writer.write("<tei xml:space=\"preserve\">\n");
                        writer.write("\t<teiHeader>\n");
                        writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                        writer.write("\t\t\t<datelines>\n");
                        writer.write("\t\t\t\t<dateline>\n");
                        writer.write(input); // unlabeled data
                        writer.write("\n\t\t\t\t</dateline>\n");
                        writer.write("\t\t\t</datelines>\n");
                        writer.close();
                    }

                    // 4a. MEDIC MODEL (from header information)
                    // path for the medic model
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.header.medic.blank.tei.xml"));
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.header.medic"));

                    // we need to rebuild the found string as it appears
                    input = "";
                    q = 0;
                    st = new StringTokenizer(rese, "\n");
                    while (st.hasMoreTokens() && (q < headerTokenization.size())) {
                        String line = st.nextToken();
                        String theTotalTok = headerTokenization.get(q).getText();
                        String theTok = headerTokenization.get(q).getText();
                        while (theTok.equals(" ") || theTok.equals("\t") || theTok.equals("\n") || theTok.equals("\r")) {
                            q++;
                            if ((q > 0) && (q < headerTokenization.size())) {
                                theTok = headerTokenization.get(q).getText();
                                theTotalTok += theTok;
                            }
                        }
                        if (line.endsWith("<medic>")) {
                            input += theTotalTok;
                        }
                        q++;
                    }

                    if (input != null && input.trim().length() > 0) {
                        // force analyser with English, to avoid bad surprise
                        List<LayoutToken> tokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(input, new Language("en", 1.0));
                        List<String> tokenizationMedic = analyzer.tokenize(input);
                        List<String> medicBlocks = new ArrayList<String>();
                        if (tokenizationMedic.size() == 0)
                            return null;
                        for (String tok : tokenizationMedic) {
                            if (tok.equals("\n")) {
                                medicBlocks.add("@newline");
                            } else if (!tok.equals(" ")) {
                                medicBlocks.add(tok + " <medic>");
                            }
                        }

                        List<OffsetPosition> locationPositions = lexicon.tokenPositionsLocationNames(tokens);
                        List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(tokens);
                        List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(tokens);
                        List<OffsetPosition> emailPositions = lexicon.tokenPositionsEmailPattern(tokens);
                        List<OffsetPosition> urlPositions = lexicon.tokenPositionsUrlPattern(tokens);
                        // we write the medic data with features
                        String featuredMedic = FeaturesVectorMedic.addFeaturesMedic(tokens, null,
                            locationPositions, titlePositions, suffixPositions, emailPositions, urlPositions);

                        if (featuredMedic != null) {
                            writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                            writer.write(featuredMedic + "\n");
                            writer.close();
                        }

                        // we write the medics data yet unlabeled
                        if (input.length() > 0) {
                            writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                            writer.write("<tei xml:space=\"preserve\">\n");
                            writer.write("\t<teiHeader>\n");
                            writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                            writer.write("\t\t\t<medics>\n");
                            writer.write("\t\t\t\t<medic>\n");
                            writer.write(input);
                            writer.write("\n\t\t\t\t</medic>\n");
                            writer.write("\t\t\t</medics>\n");
                            writer.write("\t\t</fileDesc>\n");
                            writer.write("\t</teiHeader>\n");
                            writer.write("</tei>");
                            writer.close();
                        }
                    }

                    // 5a. PERSON NAME MODEL (from medics)
                    // path for person name model
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.medic.name.blank.tei.xml"));
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.medic.name"));

                    if (input != null && input.trim().length() > 0) {
                        List<Medic> listMedics = parsers.getMedicParser().processing(input); //if the medic model exists already

                        // if the model doesn't exist, just write the text to file
                        if (listMedics != null && listMedics.size() > 0) {
                            for (Medic medic : listMedics) {
                                if (medic.getPersName() != null) { // take only the names
                                    // write the labeled data
                                    writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                                    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                                    writer.write("<tei xml:space=\"preserve\">\n");
                                    writer.write("\t<teiHeader>\n");
                                    writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                                    writer.write("\t\t\t<medics>\n");
                                    writer.write("\t\t\t\t<name>\n");
                                    writer.write(medic.getPersName() + "\n"); // unlabelled data
                                    writer.write("\t\t\t\t</name>\n");
                                    writer.write("\t\t\t</medics>\n");
                                    writer.write("\t\t</fileDesc>\n");
                                    writer.write("\t</teiHeader>\n");
                                    writer.write("</tei>");
                                    writer.close();
                                    List<LayoutToken> nameTokenizations = analyzer.tokenizeWithLayoutToken(medic.getPersName());

                                    List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(nameTokenizations);
                                    List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(nameTokenizations);

                                    // get the features for the name
                                    String featuredName = FeaturesVectorPersonName.addFeaturesName(nameTokenizations, null,
                                        titlePositions, suffixPositions);

                                    // write the data with features
                                    if (featuredName != null) {
                                        writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                                        writer.write(featuredName + "\n");
                                        writer.close();
                                    }
                                }
                            }
                        }
                    }

                    // 6. PATIENT MODEL
                    // path for patient model
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.patient.blank.tei.xml"));
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.patient"));

                    // buffer for the patients block
                    StringBuilder bufferPatient = null;
                    // we need to rebuild the found string as it appears
                    input = "";
                    q = 0;
                    st = new StringTokenizer(rese, "\n");
                    while (st.hasMoreTokens() && (q < headerTokenization.size())) {
                        String line = st.nextToken();
                        String theTotalTok = headerTokenization.get(q).getText();
                        String theTok = headerTokenization.get(q).getText();
                        while (theTok.equals(" ") || theTok.equals("\t") || theTok.equals("\n") || theTok.equals("\r")) {
                            q++;
                            if ((q > 0) && (q < headerTokenization.size())) {
                                theTok = headerTokenization.get(q).getText();
                                theTotalTok += theTok;
                            }
                        }
                        if (line.endsWith("<patient>")) {
                            input += theTotalTok;
                        }
                        q++;
                    }

                    if (input != null && input.trim().length() > 0) {
                        // force analyser with English, to avoid bad surprise
                        List<LayoutToken> tokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(input, new Language("en", 1.0));
                        List<String> tokenizationPatient = analyzer.tokenize(input);
                        List<String> patientBlocks = new ArrayList<String>();
                        if (tokenizationPatient.size() == 0)
                            return null;
                        for (String tok : tokenizationPatient) {
                            if (tok.equals("\n")) {
                                patientBlocks.add("@newline");
                            } else if (!tok.equals(" ")) {
                                patientBlocks.add(tok + " <patient>");
                            }
                        }

                        List<OffsetPosition> locationPositions = lexicon.tokenPositionsLocationNames(tokens);
                        List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(tokens);
                        List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(tokens);

                        String featuredPatient = FeaturesVectorPatient.addFeaturesPatient(tokens, null,
                            locationPositions, titlePositions, suffixPositions);

                        // we write the patient data with features
                        if (featuredPatient != null) {
                            writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                            writer.write(featuredPatient + "\n");
                            writer.close();
                        }

                        // we write the patients data yet unlabeled
                        if (input.length() > 0) {
                            writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                            writer.write("<tei xml:space=\"preserve\">\n");
                            writer.write("\t<teiHeader>\n");
                            writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                            writer.write("\t\t\t<patients>\n");
                            writer.write("\t\t\t\t<patient>\n");
                            writer.write(input);
                            writer.write("\n\t\t\t\t</patient>\n");
                            writer.write("\t\t\t</patients>\n");
                            writer.write("\t\t</fileDesc>\n");
                            writer.write("\t</teiHeader>\n");
                            writer.write("</tei>");
                            writer.close();
                        }
                    }

                    // 5b. PERSON NAME MODEL (from patients)
                    // path for person name model
                    outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.patient.name.blank.tei.xml"));
                    outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.patient.name"));

                    if (input != null && input.trim().length() > 0) {
                        List<Patient> listPatients = parsers.getPatientParser().processing(input); //if the patient model exists already

                        // if the model doesn't exist, just write the text to file
                        if (listPatients != null && listPatients.size() > 0) {
                            for (Patient patient : listPatients) {
                                if (patient.getPersName() != null) { // take only the names
                                    // write the labeled data
                                    writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                                    writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                                    writer.write("<tei xml:space=\"preserve\">\n");
                                    writer.write("\t<teiHeader>\n");
                                    writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                                    writer.write("\t\t\t<medics>\n");
                                    writer.write("\t\t\t\t<name>\n");
                                    writer.write(patient.getPersName() + "\n"); // unlabelled data
                                    writer.write("\t\t\t\t</name>\n");
                                    writer.write("\t\t\t</medics>\n");
                                    writer.write("\t\t</fileDesc>\n");
                                    writer.write("\t</teiHeader>\n");
                                    writer.write("</tei>");
                                    writer.close();
                                    List<LayoutToken> nameTokenizations = analyzer.tokenizeWithLayoutToken(patient.getPersName());

                                    List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(nameTokenizations);
                                    List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(nameTokenizations);

                                    // get the features for the name
                                    String featuredName = FeaturesVectorPersonName.addFeaturesName(nameTokenizations, null,
                                        titlePositions, suffixPositions);

                                    // write the data with features
                                    if (featuredName != null) {
                                        writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                                        writer.write(featuredName + "\n");
                                        writer.close();
                                    }
                                }
                            }
                        }
                    }
                }
            } // end of the header processing

            // 7. LEFT NOTE MEDICAL REPORT MODEL
            // path for left note model
            outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.medical.blank.tei.xml"));
            outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.medical"));

            // we take the left-note part only
            SortedSet<DocumentPiece> documentLeftNoteParts = doc.getDocumentPart(MedicalLabels.LEFTNOTE);

            if (documentLeftNoteParts != null) {
                Pair<String, List<LayoutToken>> featuredLeftNote = parsers.getLeftNoteMedicalParser().getSectionLeftNoteFeatured(doc, documentLeftNoteParts);

                if (featuredLeftNote != null) {
                    String leftNote = featuredLeftNote.getLeft(); // data with features
                    List<LayoutToken> leftNoteTokenizations = featuredLeftNote.getRight(); // tokens information
                    if ((leftNote != null) && (leftNote.trim().length() > 0)) {
                        // we write left-note data with features
                        writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                        writer.write(leftNote + "\n");
                        writer.close();

                        // write the training TEI file for left-note which reflects the extract layout of the text as
                        // extracted from the pdf
                        writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                        writer.write("<?xml version=\"1.0\" ?>\n<tei xml:space=\"preserve\">\n\t<teiHeader>\n\t\t<fileDesc xml:id=\""
                            + pdfFileName.replace(".pdf", "")
                            + "\"/>\n\t</teiHeader>\n\t<text");

                        writer.write(" xml:lang=\"fr\"");
                        writer.write(">\n\t\t<listOrg>\n");

                        StringBuilder bufferLeftNote = new StringBuilder();
                        for (LayoutToken token : leftNoteTokenizations) {
                            bufferLeftNote.append(token.getText());
                        }

                        writer.write(bufferLeftNote.toString());
                        writer.write("\n\t\t</listOrg>\n\t</text>\n</tei>\n");
                        writer.close();

                        // =============== if the model exists ===============
                        // we tag with the left-note model
                        String rese = parsers.getLeftNoteMedicalParser().label(leftNote);

                        // 4b. MEDIC MODEL (from left note information)
                        // path for the medic model
                        outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.medic.blank.tei.xml"));
                        outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.medic"));

                        // we need to rebuild the found string as it appears
                        String input = "";
                        int q = 0;
                        StringTokenizer st = new StringTokenizer(rese, "\n");
                        while (st.hasMoreTokens() && (q < leftNoteTokenizations.size())) {
                            String line = st.nextToken();
                            String theTotalTok = leftNoteTokenizations.get(q).getText();
                            String theTok = leftNoteTokenizations.get(q).getText();
                            while (theTok.equals(" ") || theTok.equals("\t") || theTok.equals("\n") || theTok.equals("\r")) {
                                q++;
                                if ((q > 0) && (q < leftNoteTokenizations.size())) {
                                    theTok = leftNoteTokenizations.get(q).getText();
                                    theTotalTok += theTok;
                                }
                            }
                            if (line.endsWith("<medic>")) {
                                input += theTotalTok;
                            }
                            q++;
                        }

                        if (input != null && input.trim().length() > 0) {
                            // force analyser with English, to avoid bad surprise
                            List<LayoutToken> tokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(input, new Language("en", 1.0));
                            List<String> tokenizationMedic = analyzer.tokenize(input);
                            List<String> medicBlocks = new ArrayList<String>();
                            if (tokenizationMedic.size() == 0)
                                return null;
                            for (String tok : tokenizationMedic) {
                                if (tok.equals("\n")) {
                                    medicBlocks.add("@newline");
                                } else if (!tok.equals(" ")) {
                                    medicBlocks.add(tok + " <medic>");
                                }
                            }

                            List<OffsetPosition> locationPositions = lexicon.tokenPositionsLocationNames(tokens);
                            List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(tokens);
                            List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(tokens);
                            List<OffsetPosition> emailPositions = lexicon.tokenPositionsEmailPattern(tokens);
                            List<OffsetPosition> urlPositions = lexicon.tokenPositionsUrlPattern(tokens);
                            // we write the medic data with features
                            String featuredMedic = FeaturesVectorMedic.addFeaturesMedic(tokens, null,
                                locationPositions, titlePositions, suffixPositions, emailPositions, urlPositions);

                            if (featuredMedic != null) {
                                writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                                writer.write(featuredMedic + "\n");
                                writer.close();
                            }

                            // we write the medics data yet unlabeled
                            if (input.length() > 0) {
                                writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                                writer.write("<tei xml:space=\"preserve\">\n");
                                writer.write("\t<teiHeader>\n");
                                writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                                writer.write("\t\t\t<medics>\n");
                                writer.write("\t\t\t\t<medic>\n");
                                writer.write(input);
                                writer.write("\n\t\t\t\t</medic>\n");
                                writer.write("\t\t\t</medics>\n");
                                writer.write("\t\t</fileDesc>\n");
                                writer.write("\t</teiHeader>\n");
                                writer.write("</tei>");
                                writer.close();
                            }
                        }

                        // 5. ORGANIZATION MODEL (from left note information)
                        // path for the organization model
                        outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.organization.blank.tei.xml"));
                        outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.left.note.organization"));

                        // we need to rebuild the found string as it appears
                        input = "";
                        q = 0;
                        st = new StringTokenizer(rese, "\n");
                        while (st.hasMoreTokens() && (q < leftNoteTokenizations.size())) {
                            String line = st.nextToken();
                            String theTotalTok = leftNoteTokenizations.get(q).getText();
                            String theTok = leftNoteTokenizations.get(q).getText();
                            while (theTok.equals(" ") || theTok.equals("\t") || theTok.equals("\n") || theTok.equals("\r")) {
                                q++;
                                if ((q > 0) && (q < leftNoteTokenizations.size())) {
                                    theTok = leftNoteTokenizations.get(q).getText();
                                    theTotalTok += theTok;
                                }
                            }
                            if (line.endsWith("<ghu>") || line.endsWith("<chu>") || line.endsWith("<dmu>") ||
                                line.endsWith("<pole>") || line.endsWith("<institution>") || line.endsWith("<university>") ||
                                line.endsWith("<site>") || line.endsWith("<hospital>") || line.endsWith("<center>") ||
                                line.endsWith("<service>") || line.endsWith("<department>") || line.endsWith("<unit>") ||
                                line.endsWith("<sub>") || line.endsWith("<organization>")) {
                                input += theTotalTok;
                            }
                            q++;
                        }

                        if (input != null && input.trim().length() > 0) {
                            // force analyser with English, to avoid bad surprise
                            List<LayoutToken> tokens = GrobidAnalyzer.getInstance().tokenizeWithLayoutToken(input, new Language("en", 1.0));
                            List<String> tokenizationOrg = analyzer.tokenize(input);
                            List<String> orgBlocks = new ArrayList<String>();
                            if (tokenizationOrg.size() == 0)
                                return null;
                            for (String tok : tokenizationOrg) {
                                if (tok.equals("\n")) {
                                    orgBlocks.add("@newline");
                                } else if (!tok.equals(" ")) {
                                    orgBlocks.add(tok + " <org>");
                                }
                            }

                            List<OffsetPosition> locationPositions = lexicon.tokenPositionsLocationNames(tokens);
                            List<OffsetPosition> titlePositions = lexicon.tokenPositionsPersonTitle(tokens);
                            List<OffsetPosition> suffixPositions = lexicon.tokenPositionsPersonSuffix(tokens);
                            List<OffsetPosition> emailPositions = lexicon.tokenPositionsEmailPattern(tokens);
                            List<OffsetPosition> urlPositions = lexicon.tokenPositionsUrlPattern(tokens);
                            // we write the medic data with features
                            String featuredMedic = FeaturesVectorMedic.addFeaturesMedic(tokens, null,
                                locationPositions, titlePositions, suffixPositions, emailPositions, urlPositions);

                            if (featuredMedic != null) {
                                writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                                writer.write(featuredMedic + "\n");
                                writer.close();
                            }

                            // we write the organization data yet unlabeled
                            if (input.length() > 0) {
                                writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                                writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
                                writer.write("<tei xml:space=\"preserve\">\n");
                                writer.write("\t<teiHeader>\n");
                                writer.write("\t\t<fileDesc xml:id=\"" + pdfFileName.replace(".pdf", "") + "\">\n");
                                writer.write("\t\t\t<listOrg>\n");
                                writer.write("\t\t\t\t<org>\n");
                                writer.write(input);
                                writer.write("\n\t\t\t\t</org>\n");
                                writer.write("\t\t\t</listOrg>\n");
                                writer.write("\t\t</fileDesc>\n");
                                writer.write("\t</teiHeader>\n");
                                writer.write("</tei>");
                                writer.close();
                            }
                        }
                    }
                }
            }

            // 8. FULL-MEDICAL-TEXT MODEL
            // path for blank full-medical-text model
            outputTEIFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.full.medical.text.blank.tei.xml"));
            outputRawFile = new File(pathOutput + File.separator + pdfFileName.replace(".pdf", ".training.full.medical.text"));

            // first, call the medical-report-segmenter model to have high level segmentation
            doc = parsers.getMedicalReportSegmenterParser().processing(documentSource, GrobidAnalysisConfig.defaultInstance());

            // FULL-MEDICAL-TEXT MODEL (body part)
            SortedSet<DocumentPiece> documentBodyParts = doc.getDocumentPart(MedicalLabels.BODY);
            if (documentBodyParts != null) {
                Pair<String, LayoutTokenization> featSeg = getBodyTextFeatured(doc, documentBodyParts);
                if (featSeg != null) {
                    String bodytext = featSeg.getLeft(); // featured
                    List<LayoutToken> tokenizationsBody = featSeg.getRight().getTokenization();

                    // we write the full text untagged
                    writer = new OutputStreamWriter(new FileOutputStream(outputRawFile, false), StandardCharsets.UTF_8);
                    writer.write(bodytext + "\n");
                    writer.close();

                    StringBuilder bufferBody = new StringBuilder();

                    // just write the text without any label
                    for (LayoutToken token : tokenizationsBody) {
                        bufferBody.append(token.getText());
                    }

                    // write the TEI file to reflect the extract layout of the text as extracted from the pdf
                    writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);
                    if (id == -1) {
                        writer.write("<?xml version=\"1.0\" ?>\n<tei xml:space=\"preserve\">\n\t<teiHeader/>\n\t<text xml:lang=\"fr\">\n");
                    } else {
                        writer.write("<?xml version=\"1.0\" ?>\n<tei xml:space=\"preserve\">\n\t<teiHeader>\n\t\t<fileDesc xml:id=\"" + id +
                            "\"/>\n\t</teiHeader>\n\t<text xml:lang=\"fr\">\n");
                    }
                    writer.write(bufferBody.toString());
                    writer.write("\n\t</text>\n</tei>\n");
                    writer.close();

                }
            }

        } catch (Exception e) {
            throw new GrobidException("An exception occurred while running Grobid training" +
                " data generation for full text.", e);
        } finally {
            DocumentSource.close(documentSource, true, true, true);
        }

        return doc;
    }

    /**
     * Extract results from a labelled full text in the training format without any string modification.
     *
     * @param result        result
     * @param tokenizations tokens
     * @return extraction
     */
    private StringBuilder trainingExtraction(String result,
                                             List<LayoutToken> tokenizations) {
        // this is the main buffer for the whole full text
        StringBuilder buffer = new StringBuilder();
        try {
            StringTokenizer st = new StringTokenizer(result, "\n");
            String s1 = null;
            String s2 = null;
            String lastTag = null;
            //System.out.println(tokenizations.toString());
            //System.out.println(result);
            // current token position
            int p = 0;
            boolean start = true;
            boolean openFigure = false;
            boolean headFigure = false;
            boolean descFigure = false;
            boolean tableBlock = false;

            while (st.hasMoreTokens()) {
                boolean addSpace = false;
                String tok = st.nextToken().trim();

                if (tok.length() == 0) {
                    continue;
                }
                StringTokenizer stt = new StringTokenizer(tok, " \t");
                List<String> localFeatures = new ArrayList<String>();
                int i = 0;

                boolean newLine = false;
                int ll = stt.countTokens();
                while (stt.hasMoreTokens()) {
                    String s = stt.nextToken().trim();
                    if (i == 0) {
                        s2 = TextUtilities.HTMLEncode(s); // lexical token
                        int p0 = p;
                        boolean strop = false;
                        while ((!strop) && (p < tokenizations.size())) {
                            String tokOriginal = tokenizations.get(p).t();
                            if (tokOriginal.equals(" ")
                                || tokOriginal.equals("\u00A0")) {
                                addSpace = true;
                            } else if (tokOriginal.equals("\n")) {
                                newLine = true;
                            } else if (tokOriginal.equals(s)) {
                                strop = true;
                            }
                            p++;
                        }
                        if (p == tokenizations.size()) {
                            // either we are at the end of the header, or we might have
                            // a problematic token in tokenization for some reasons
                            if ((p - p0) > 2) {
                                // we loose the synchronicity, so we reinit p for the next token
                                p = p0;
                            }
                        }
                    } else if (i == ll - 1) {
                        s1 = s; // current tag
                    } else {
                        if (s.equals("LINESTART"))
                            newLine = true;
                        localFeatures.add(s);
                    }
                    i++;
                }

                if (newLine && !start) {
                    buffer.append("<lb/>");
                }

                String lastTag0 = null;
                if (lastTag != null) {
                    if (lastTag.startsWith("I-")) {
                        lastTag0 = lastTag.substring(2, lastTag.length());
                    } else {
                        lastTag0 = lastTag;
                    }
                }
                String currentTag0 = null;
                if (s1 != null) {
                    if (s1.startsWith("I-")) {
                        currentTag0 = s1.substring(2, s1.length());
                    } else {
                        currentTag0 = s1;
                    }
                }

                boolean closeParagraph = false;
                if (lastTag != null) {
                    closeParagraph = testClosingTag(buffer, currentTag0, lastTag0, s1);
                }

                boolean output;

                output = writeField(buffer, s1, lastTag0, s2, "<other>",
                    "<note type=\"other\">", addSpace, 3, false);

                // for paragraph we must distinguish starting and closing tags
                if (!output) {
                    if (closeParagraph) {
                        output = writeFieldBeginEnd(buffer, s1, "", s2, "<paragraph>",
                            "<p>", addSpace, 3, false);
                    } else {
                        output = writeFieldBeginEnd(buffer, s1, lastTag, s2, "<paragraph>",
                            "<p>", addSpace, 3, false);
                    }
                }
                if (!output) {
                    output = writeField(buffer, s1, lastTag0, s2, "<table>",
                        "<figure type=\"table\">", addSpace, 3, false);
                }
                if (!output) {
                    output = writeField(buffer, s1, lastTag0, s2, "<table_marker>", "<ref type=\"table\">",
                        addSpace, 3, false);
                }
                if (!output) {
                    output = writeField(buffer, s1, lastTag0, s2, "<figure>",
                        "<figure>", addSpace, 3, false);
                }
                if (!output) {
                    output = writeField(buffer, s1, lastTag0, s2, "<figure_marker>",
                        "<ref type=\"figure\">", addSpace, 3, false);
                }
                if (!output) {
                    output = writeField(buffer, s1, lastTag0, s2, "<title>",
                        "<title>", addSpace, 3, false);
                }
                if (!output) {
                    output = writeField(buffer, s1, lastTag0, s2, "<section>",
                        "<head level=\"1\">", addSpace, 3, false);
                }
                if (!output) {
                    output = writeField(buffer, s1, lastTag0, s2, "<subsection>",
                        "<head level=\"2\">", addSpace, 3, false);
                }
                if (!output) {
                    output = writeField(buffer, s1, lastTag0, s2, "<headnote>",
                        "<note place=\"headnote\">", addSpace, 3, false);
                }
                if (!output) {
                    output = writeField(buffer, s1, lastTag0, s2, "<footnote>",
                        "<note place=\"footnote\">", addSpace, 3, false);
                }
                // for items we must distinguish starting and closing tags
                if (!output) {
                    output = writeFieldBeginEnd(buffer, s1, lastTag, s2, "<item>",
                        "<item>", addSpace, 3, false);
                }

                lastTag = s1;

                if (!st.hasMoreTokens()) {
                    if (lastTag != null) {
                        testClosingTag(buffer, "", currentTag0, s1);
                    }
                }
                if (start) {
                    start = false;
                }
            }

            return buffer;
        } catch (Exception e) {
            e.printStackTrace();
            throw new GrobidException("An exception occurred while running Grobid.", e);
        }
    }

    /**
     * @param buffer   buffer
     * @param s1
     * @param lastTag0
     * @param s2
     * @param field
     * @param outField
     * @param addSpace
     * @param nbIndent
     * @return
     */
    public static boolean writeField(StringBuilder buffer,
                                     String s1,
                                     String lastTag0,
                                     String s2,
                                     String field,
                                     String outField,
                                     boolean addSpace,
                                     int nbIndent,
                                     boolean generateIDs) {
        boolean result = false;
        if (s1 == null) {
            return result;
        }
        if ((s1.equals(field)) || (s1.equals("I-" + field))) {
            result = true;
            String divID = null;
            if (generateIDs) {
                divID = KeyGen.getKey().substring(0, 7);
                if (outField.charAt(outField.length() - 2) == '>')
                    outField = outField.substring(0, outField.length() - 2) + " xml:id=\"_" + divID + "\">";
            }
            if (s1.equals(lastTag0) || s1.equals("I-" + lastTag0)) {
                if (addSpace)
                    buffer.append(" ").append(s2);
                else
                    buffer.append(s2);
            } else if (field.equals("<figure_marker>")) {
                if (addSpace)
                    buffer.append(" ").append(outField).append(s2);
                else
                    buffer.append(outField).append(s2);
            } else if (field.equals("<table_marker>")) {
                if (addSpace)
                    buffer.append(" ").append(outField).append(s2);
                else
                    buffer.append(outField).append(s2);
            } else if (lastTag0 == null) {
                for (int i = 0; i < nbIndent; i++) {
                    buffer.append("\t");
                }
                buffer.append(outField).append(s2);
            } else if (!lastTag0.equals("<figure_marker>")) {
                for (int i = 0; i < nbIndent; i++) {
                    buffer.append("\t");
                }
                buffer.append(outField).append(s2);
            } else {
                if (addSpace)
                    buffer.append(" ").append(s2);
                else
                    buffer.append(s2);
            }
        }
        return result;
    }

    /**
     * This is for writing fields for fields where begin and end of field matter, like paragraph or item
     *
     * @param buffer
     * @param s1
     * @param lastTag0
     * @param s2
     * @param field
     * @param outField
     * @param addSpace
     * @param nbIndent
     * @return
     */
    public static boolean writeFieldBeginEnd(StringBuilder buffer,
                                             String s1,
                                             String lastTag0,
                                             String s2,
                                             String field,
                                             String outField,
                                             boolean addSpace,
                                             int nbIndent,
                                             boolean generateIDs) {
        boolean result = false;
        if (s1 == null) {
            return false;
        }
        if ((s1.equals(field)) || (s1.equals("I-" + field))) {
            result = true;
            if (lastTag0 == null) {
                lastTag0 = "";
            }
            String divID;
            if (generateIDs) {
                divID = KeyGen.getKey().substring(0, 7);
                if (outField.charAt(outField.length() - 2) == '>')
                    outField = outField.substring(0, outField.length() - 2) + " xml:id=\"_" + divID + "\">";
            }
            if (lastTag0.equals("I-" + field)) {
                if (addSpace)
                    buffer.append(" ").append(s2);
                else
                    buffer.append(s2);
            } else if (lastTag0.equals(field) && s1.equals(field)) {
                if (addSpace)
                    buffer.append(" ").append(s2);
                else
                    buffer.append(s2);
            } else if (!lastTag0.endsWith("<figure_marker>") && !lastTag0.endsWith("<table_marker>")) {
                for (int i = 0; i < nbIndent; i++) {
                    buffer.append("\t");
                }
                buffer.append(outField).append(s2);
            } else {
                if (addSpace)
                    buffer.append(" ").append(s2);
                else
                    buffer.append(s2);
            }
        }
        return result;
    }

    /**
     * @param buffer
     * @param currentTag0
     * @param lastTag0
     * @param currentTag
     * @return
     */
    private static boolean testClosingTag(StringBuilder buffer,
                                          String currentTag0,
                                          String lastTag0,
                                          String currentTag) {
        boolean res = false;

        if (!currentTag0.equals(lastTag0) || currentTag.equals("I-<paragraph>") || currentTag.equals("I-<item>")) {
            if (currentTag0.equals("<figure_marker>") || currentTag0.equals("<table_marker>")) {
                return res;
            }

            res = false;
            // we close the current tag
            if (lastTag0.equals("<other>")) {
                buffer.append("</note>\n\n");

            } else if (lastTag0.equals("<paragraph>") &&
                !currentTag0.equals("<table_marker>") &&
                !currentTag0.equals("<figure_marker>")
            ) {
                buffer.append("</p>\n\n");
                res = true;

            } else if (lastTag0.equals("<title>")) {
                buffer.append("</title>\n");
            } else if (lastTag0.equals("<section>")) {
                buffer.append("</head>\n\n");
            } else if (lastTag0.equals("<subsection>")) {
                buffer.append("</head>\n\n");
            } else if (lastTag0.equals("<table>")) {
                buffer.append("</figure>\n\n");
            } else if (lastTag0.equals("<figure>")) {
                buffer.append("</figure>\n\n");
            } else if (lastTag0.equals("<item>")) {
                buffer.append("</item>\n\n");
            } else if (lastTag0.equals("<figure_marker>")) {
                buffer.append("</ref>");
            } else if (lastTag0.equals("<table_marker>")) {
                buffer.append("</ref>");
            } else {
                res = false;

            }
        }
        return res;
    }


    /**
     * Process figures identified by the full text model
     */
    protected List<Figure> processFigures(String rese, List<LayoutToken> layoutTokens, Document doc) {

        List<Figure> results = new ArrayList<>();

        TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.FULL_MEDICAL_TEXT, rese, layoutTokens, true);

        for (TaggingTokenCluster cluster : Iterables.filter(clusteror.cluster(),
            new TaggingTokenClusteror.LabelTypePredicate(TaggingLabels.FIGURE))) {
            List<LayoutToken> tokenizationFigure = cluster.concatTokens();
            Figure result = parsers.getFigureParser().processing(
                tokenizationFigure,
                cluster.getFeatureBlock()
            );
            SortedSet<Integer> blockPtrs = new TreeSet<>();
            for (LayoutToken lt : tokenizationFigure) {
                if (!LayoutTokensUtil.spaceyToken(lt.t()) && !LayoutTokensUtil.newLineToken(lt.t())) {
                    blockPtrs.add(lt.getBlockPtr());
                }
            }
            result.setBlockPtrs(blockPtrs);

            result.setLayoutTokens(tokenizationFigure);

            // the first token could be a space from previous page
            for (LayoutToken lt : tokenizationFigure) {
                if (!LayoutTokensUtil.spaceyToken(lt.t()) && !LayoutTokensUtil.newLineToken(lt.t())) {
                    result.setPage(lt.getPage());
                    break;
                }
            }

            results.add(result);
            result.setId("" + (results.size() - 1));
        }

        doc.setFigures(results);
        doc.assignGraphicObjectsToFigures();
        return results;
    }

    /**
     * Create training data for the figures as identified by the full text model.
     * Return the pair (TEI fragment, CRF raw data).
     */
    protected Pair<String, String> processTrainingDataFigures(String rese,
                                                              List<LayoutToken> tokenizations, String id) {
        StringBuilder tei = new StringBuilder();
        StringBuilder featureVector = new StringBuilder();
        int nb = 0;
        StringTokenizer st1 = new StringTokenizer(rese, "\n");
        boolean openFigure = false;
        StringBuilder figureBlock = new StringBuilder();
        List<LayoutToken> tokenizationsFigure = new ArrayList<>();
        List<LayoutToken> tokenizationsBuffer = null;
        int p = 0; // position in tokenizations
        int i = 0;
        while (st1.hasMoreTokens()) {
            String row = st1.nextToken();
            String[] s = row.split("\t");
            String token = s[0].trim();
            int p0 = p;
            boolean strop = false;
            tokenizationsBuffer = new ArrayList<>();
            while ((!strop) && (p < tokenizations.size())) {
                String tokOriginal = tokenizations.get(p).getText().trim();
                if (openFigure)
                    tokenizationsFigure.add(tokenizations.get(p));
                tokenizationsBuffer.add(tokenizations.get(p));
                if (tokOriginal.equals(token)) {
                    strop = true;
                }
                p++;
            }
            if (p == tokenizations.size()) {
                // either we are at the end of the header, or we might have
                // a problematic token in tokenization for some reasons
                if ((p - p0) > 2) {
                    // we loose the synchronicity, so we reinit p for the next token
                    p = p0;
                    continue;
                }
            }

            int ll = s.length;
            String label = s[ll - 1];
            String plainLabel = GenericTaggerUtils.getPlainLabel(label);
            if (label.equals("<figure>") || ((label.equals("I-<figure>") && !openFigure))) {
                if (!openFigure) {
                    openFigure = true;
                    tokenizationsFigure.addAll(tokenizationsBuffer);
                }
                // we remove the label in the CRF row
                int ind = row.lastIndexOf("\t");
                figureBlock.append(row, 0, ind).append("\n");
            } else if (label.equals("I-<figure>") || openFigure) {
                // remove last tokens
                if (tokenizationsFigure.size() > 0) {
                    int nbToRemove = tokenizationsBuffer.size();
                    for (int q = 0; q < nbToRemove; q++)
                        tokenizationsFigure.remove(tokenizationsFigure.size() - 1);
                }
                // parse the recognized figure area
//System.out.println(tokenizationsFigure.toString());
//System.out.println(figureBlock.toString());
                //adjustment
                if ((p != tokenizations.size()) && (tokenizations.get(p).getText().equals("\n") ||
                    tokenizations.get(p).getText().equals("\r") ||
                    tokenizations.get(p).getText().equals(" "))) {
                    tokenizationsFigure.add(tokenizations.get(p));
                    p++;
                }
                while ((tokenizationsFigure.size() > 0) &&
                    (tokenizationsFigure.get(0).getText().equals("\n") ||
                        tokenizationsFigure.get(0).getText().equals(" ")))
                    tokenizationsFigure.remove(0);

                // process the "accumulated" figure
                Pair<String, String> trainingData = parsers.getFigureParser()
                    .createTrainingData(tokenizationsFigure, figureBlock.toString(), "Fig" + nb);
                tokenizationsFigure = new ArrayList<>();
                figureBlock = new StringBuilder();
                if (trainingData != null) {
                    if (tei.length() == 0) {
                        tei.append(parsers.getFigureParser().getTEIHeader(id)).append("\n\n");
                    }
                    if (trainingData.getLeft() != null)
                        tei.append(trainingData.getLeft()).append("\n\n");
                    if (trainingData.getRight() != null)
                        featureVector.append(trainingData.getRight()).append("\n\n");
                }

                if (label.equals("I-<figure>")) {
                    tokenizationsFigure.addAll(tokenizationsBuffer);
                    int ind = row.lastIndexOf("\t");
                    figureBlock.append(row.substring(0, ind)).append("\n");
                } else {
                    openFigure = false;
                }
                nb++;
            } else
                openFigure = false;
        }

        // If there still an open figure
        if (openFigure) {
            while ((tokenizationsFigure.size() > 0) &&
                (tokenizationsFigure.get(0).getText().equals("\n") ||
                    tokenizationsFigure.get(0).getText().equals(" ")))
                tokenizationsFigure.remove(0);

            // process the "accumulated" figure
            Pair<String, String> trainingData = parsers.getFigureParser()
                .createTrainingData(tokenizationsFigure, figureBlock.toString(), "Fig" + nb);
            if (tei.length() == 0) {
                tei.append(parsers.getFigureParser().getTEIHeader(id)).append("\n\n");
            }
            if (trainingData.getLeft() != null)
                tei.append(trainingData.getLeft()).append("\n\n");
            if (trainingData.getRight() != null)
                featureVector.append(trainingData.getRight()).append("\n\n");
        }

        if (tei.length() != 0) {
            tei.append("\n    </text>\n" +
                "</tei>\n");
        }
        return Pair.of(tei.toString(), featureVector.toString());
    }

    /**
     * Process tables identified by the full text model
     */
    protected List<Table> processTables(String rese,
                                        List<LayoutToken> tokenizations,
                                        Document doc) {
        List<Table> results = new ArrayList<>();
        TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.FULL_MEDICAL_TEXT, rese, tokenizations, true);

        for (TaggingTokenCluster cluster : Iterables.filter(clusteror.cluster(),
            new TaggingTokenClusteror.LabelTypePredicate(TaggingLabels.TABLE))) {
            List<LayoutToken> tokenizationTable = cluster.concatTokens();
            List<Table> localResults = parsers.getTableParser().processing(
                tokenizationTable,
                cluster.getFeatureBlock()
            );

            for (Table result : localResults) {
                List<LayoutToken> localTokenizationTable = result.getLayoutTokens();
                //result.setLayoutTokens(tokenizationTable);

                // block setting: we restrict to the tokenization of this particulart table
                SortedSet<Integer> blockPtrs = new TreeSet<>();
                for (LayoutToken lt : localTokenizationTable) {
                    if (!LayoutTokensUtil.spaceyToken(lt.t()) && !LayoutTokensUtil.newLineToken(lt.t())) {
                        blockPtrs.add(lt.getBlockPtr());
                    }
                }
                result.setBlockPtrs(blockPtrs);

                // page setting: the first token could be a space from previous page
                for (LayoutToken lt : localTokenizationTable) {
                    if (!LayoutTokensUtil.spaceyToken(lt.t()) && !LayoutTokensUtil.newLineToken(lt.t())) {
                        result.setPage(lt.getPage());
                        break;
                    }
                }
                results.add(result);
                result.setId("" + (results.size() - 1));
            }
        }

        doc.setTables(results);
        doc.postProcessTables();

        return results;
    }

    /**
     * Create training data for the table as identified by the full text model.
     * Return the pair (TEI fragment, CRF raw data).
     */
    protected Pair<String, String> processTrainingDataTables(String rese,
                                                             List<LayoutToken> tokenizations, String id) {
        StringBuilder tei = new StringBuilder();
        StringBuilder featureVector = new StringBuilder();
        int nb = 0;
        StringTokenizer st1 = new StringTokenizer(rese, "\n");
        boolean openTable = false;
        StringBuilder tableBlock = new StringBuilder();
        List<LayoutToken> tokenizationsTable = new ArrayList<LayoutToken>();
        List<LayoutToken> tokenizationsBuffer = null;
        int p = 0; // position in tokenizations
        int i = 0;
        while (st1.hasMoreTokens()) {
            String row = st1.nextToken();
            String[] s = row.split("\t");
            String token = s[0].trim();
//System.out.println(s0 + "\t" + tokenizations.get(p).getText().trim());
            int p0 = p;
            boolean strop = false;
            tokenizationsBuffer = new ArrayList<LayoutToken>();
            while ((!strop) && (p < tokenizations.size())) {
                String tokOriginal = tokenizations.get(p).getText().trim();
                if (openTable)
                    tokenizationsTable.add(tokenizations.get(p));
                tokenizationsBuffer.add(tokenizations.get(p));
                if (tokOriginal.equals(token)) {
                    strop = true;
                }
                p++;
            }
            if (p == tokenizations.size()) {
                // either we are at the end of the header, or we might have
                // a problematic token in tokenization for some reasons
                if ((p - p0) > 2) {
                    // we loose the synchronicity, so we reinit p for the next token
                    p = p0;
                    continue;
                }
            }

            int ll = s.length;
            String label = s[ll - 1];
            String plainLabel = GenericTaggerUtils.getPlainLabel(label);
            if (label.equals("<table>") || ((label.equals("I-<table>") && !openTable))) {
                if (!openTable) {
                    openTable = true;
                    tokenizationsTable.addAll(tokenizationsBuffer);
                }
                // we remove the label in the CRF row
                int ind = row.lastIndexOf("\t");
                tableBlock.append(row.substring(0, ind)).append("\n");
            } else if (label.equals("I-<table>") || openTable) {
                // remove last tokens
                if (tokenizationsTable.size() > 0) {
                    int nbToRemove = tokenizationsBuffer.size();
                    for (int q = 0; q < nbToRemove; q++)
                        tokenizationsTable.remove(tokenizationsTable.size() - 1);
                }
                // parse the recognized table area
//System.out.println(tokenizationsTable.toString());
//System.out.println(tableBlock.toString());
                //adjustment
                if ((p != tokenizations.size()) && (tokenizations.get(p).getText().equals("\n") ||
                    tokenizations.get(p).getText().equals("\r") ||
                    tokenizations.get(p).getText().equals(" "))) {
                    tokenizationsTable.add(tokenizations.get(p));
                    p++;
                }
                while ((tokenizationsTable.size() > 0) &&
                    (tokenizationsTable.get(0).getText().equals("\n") ||
                        tokenizationsTable.get(0).getText().equals(" ")))
                    tokenizationsTable.remove(0);

                // process the "accumulated" table
                Pair<String, String> trainingData = parsers.getTableParser().createTrainingData(tokenizationsTable, tableBlock.toString(), "Fig" + nb);
                tokenizationsTable = new ArrayList<>();
                tableBlock = new StringBuilder();
                if (trainingData != null) {
                    if (tei.length() == 0) {
                        tei.append(parsers.getTableParser().getTEIHeader(id)).append("\n\n");
                    }
                    if (trainingData.getLeft() != null)
                        tei.append(trainingData.getLeft()).append("\n\n");
                    if (trainingData.getRight() != null)
                        featureVector.append(trainingData.getRight()).append("\n\n");
                }
                if (label.equals("I-<table>")) {
                    tokenizationsTable.addAll(tokenizationsBuffer);
                    int ind = row.lastIndexOf("\t");
                    tableBlock.append(row.substring(0, ind)).append("\n");
                } else {
                    openTable = false;
                }
                nb++;
            } else
                openTable = false;
        }

        // If there still an open table
        if (openTable) {
            while ((tokenizationsTable.size() > 0) &&
                (tokenizationsTable.get(0).getText().equals("\n") ||
                    tokenizationsTable.get(0).getText().equals(" ")))
                tokenizationsTable.remove(0);

            // process the "accumulated" figure
            Pair<String, String> trainingData = parsers.getTableParser()
                .createTrainingData(tokenizationsTable, tableBlock.toString(), "Fig" + nb);
            if (tei.length() == 0) {
                tei.append(parsers.getTableParser().getTEIHeader(id)).append("\n\n");
            }
            if (trainingData.getLeft() != null)
                tei.append(trainingData.getLeft()).append("\n\n");
            if (trainingData.getRight() != null)
                featureVector.append(trainingData.getRight()).append("\n\n");
        }

        if (tei.length() != 0) {
            tei.append("\n    </text>\n" +
                "</tei>\n");
        }
        return Pair.of(tei.toString(), featureVector.toString());
    }

    /**
     * Ensure consistent use of callouts in the entire document body
     */
    private List<MarkerType> postProcessCallout(String result, LayoutTokenization layoutTokenization) {
        if (layoutTokenization == null)
            return null;

        List<LayoutToken> tokenizations = layoutTokenization.getTokenization();

        TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.FULLTEXT, result, tokenizations);
        String tokenLabel = null;
        List<TaggingTokenCluster> clusters = clusteror.cluster();

        MarkerType majorityReferenceMarkerType = MarkerType.UNKNOWN;
        MarkerType majorityFigureMarkerType = MarkerType.UNKNOWN;
        MarkerType majorityTableMarkerType = MarkerType.UNKNOWN;
        MarkerType majorityEquationarkerType = MarkerType.UNKNOWN;

        Map<MarkerType, Integer> referenceMarkerTypeCounts = new HashMap<>();
        Map<MarkerType, Integer> figureMarkerTypeCounts = new HashMap<>();
        Map<MarkerType, Integer> tableMarkerTypeCounts = new HashMap<>();
        Map<MarkerType, Integer> equationMarkerTypeCounts = new HashMap<>();

        List<String> referenceMarkerSeen = new ArrayList<>();
        List<String> figureMarkerSeen = new ArrayList<>();
        List<String> tableMarkerSeen = new ArrayList<>();
        List<String> equationMarkerSeen = new ArrayList<>();

        for (TaggingTokenCluster cluster : clusters) {
            if (cluster == null) {
                continue;
            }

            TaggingLabel clusterLabel = cluster.getTaggingLabel();
            if (TEIFormatter.MARKER_LABELS.contains(clusterLabel)) {
                List<LayoutToken> refTokens = cluster.concatTokens();
                refTokens = LayoutTokensUtil.dehyphenize(refTokens);
                String refText = LayoutTokensUtil.toText(refTokens);
                refText = refText.replace("\n", "");
                refText = refText.replace(" ", "");
                if (refText.trim().length() == 0)
                    continue;

                if (clusterLabel.equals(TaggingLabels.FIGURE_MARKER)) {
                    if (figureMarkerSeen.contains(refText)) {
                        // already seen reference marker sequence, we skip it
                        continue;
                    }
                    MarkerType localMarkerType = CalloutAnalyzer.getCalloutType(refTokens);
                    if (figureMarkerTypeCounts.get(localMarkerType) == null)
                        figureMarkerTypeCounts.put(localMarkerType, 1);
                    else
                        figureMarkerTypeCounts.put(localMarkerType, figureMarkerTypeCounts.get(localMarkerType) + 1);

                    if (!figureMarkerSeen.contains(refText))
                        figureMarkerSeen.add(refText);
                } else if (clusterLabel.equals(TaggingLabels.TABLE_MARKER)) {
                    if (tableMarkerSeen.contains(refText)) {
                        // already seen reference marker sequence, we skip it
                        continue;
                    }
                    MarkerType localMarkerType = CalloutAnalyzer.getCalloutType(refTokens);
                    if (tableMarkerTypeCounts.get(localMarkerType) == null)
                        tableMarkerTypeCounts.put(localMarkerType, 1);
                    else
                        tableMarkerTypeCounts.put(localMarkerType, tableMarkerTypeCounts.get(localMarkerType) + 1);

                    if (!tableMarkerSeen.contains(refText))
                        tableMarkerSeen.add(refText);
                }
            }
        }

        majorityReferenceMarkerType = getBestType(referenceMarkerTypeCounts);
        majorityFigureMarkerType = getBestType(figureMarkerTypeCounts);
        majorityTableMarkerType = getBestType(tableMarkerTypeCounts);
        majorityEquationarkerType = getBestType(equationMarkerTypeCounts);

/*System.out.println("majorityReferenceMarkerType: " + majorityReferenceMarkerType);
System.out.println("majorityFigureMarkerType: " + majorityFigureMarkerType);
System.out.println("majorityTableMarkerType: " + majorityTableMarkerType);
System.out.println("majorityEquationarkerType: " + majorityEquationarkerType);*/

        return Arrays.asList(majorityReferenceMarkerType, majorityFigureMarkerType, majorityTableMarkerType, majorityEquationarkerType);
    }

    private static MarkerType getBestType(Map<MarkerType, Integer> markerTypeCount) {
        MarkerType bestType = MarkerType.UNKNOWN;
        int maxCount = 0;
        for (Map.Entry<MarkerType, Integer> entry : markerTypeCount.entrySet()) {
            if (entry.getValue() > maxCount) {
                bestType = entry.getKey();
                maxCount = entry.getValue();
            }
        }
        return bestType;
    }

    /**
     * Create the TEI representation for a document based on the parsed header, left-note
     * and body sections.
     */
    private void toTEI(Document doc,
                       String reseBody,
                       String reseAnnex,
                       LayoutTokenization layoutTokenization,
                       List<LayoutToken> tokenizationsAnnex,
                       HeaderMedicalItem resHeader,
                       LeftNoteMedicalItem resLeftNote,
                       String strLeftNote,
                       List<Figure> figures,
                       List<Table> tables,
                       List<CalloutAnalyzer.MarkerType> markerTypes,
                       GrobidAnalysisConfig config) {
        if (doc.getBlocks() == null) {
            return;
        }
        TEIFormatter teiFormatter = new TEIFormatter(doc, this);
        StringBuilder tei;
        try {
            // header and left-note
            tei = teiFormatter.toTEIHeaderLeftNote(resHeader, resLeftNote, strLeftNote, null, config);

            // body
            tei = teiFormatter.toTEIBody(tei, reseBody, resHeader, layoutTokenization, figures, tables, markerTypes, doc, config);

            tei.append("\t\t<back>\n");

            // acknowledgement is in the back
            SortedSet<DocumentPiece> documentAcknowledgementParts =
                doc.getDocumentPart(MedicalLabels.ACKNOWLEDGEMENT);
            Pair<String, LayoutTokenization> featSeg =
                getBodyTextFeatured(doc, documentAcknowledgementParts);
            List<LayoutToken> tokenizationsAcknowledgement;
            if (featSeg != null) {
                // if featSeg is null, it usually means that no body segment is found in the
                // document segmentation
                String acknowledgementText = featSeg.getLeft();
                tokenizationsAcknowledgement = featSeg.getRight().getTokenization();
                String reseAcknowledgement = null;
                if ((acknowledgementText != null) && (acknowledgementText.length() > 0))
                    reseAcknowledgement = label(acknowledgementText);
                tei = teiFormatter.toTEIAcknowledgement(tei, reseAcknowledgement,
                    tokenizationsAcknowledgement, config);
            }

            tei = teiFormatter.toTEIAnnex(tei, reseAnnex, resHeader,
                tokenizationsAnnex, markerTypes, doc, config);

            tei.append("\t\t</back>\n");

            tei.append("\t</text>\n");
            tei.append("</TEI>\n");
        } catch (Exception e) {
            throw new GrobidException("An exception occurred while running Grobid.", e);
        }
        doc.setTei(tei.toString());
    }

    private static List<TaggingLabel> inlineFullTextLabels = Arrays.asList(MedicalLabels.TABLE_MARKER, MedicalLabels.FIGURE_MARKER);

    public static List<LayoutTokenization> getDocumentFullTextTokens(List<TaggingLabel> labels, String labeledResult, List<LayoutToken> tokenizations) {
        TaggingTokenClusteror clusteror = new TaggingTokenClusteror(GrobidModels.FULL_MEDICAL_TEXT, labeledResult, tokenizations);
        List<TaggingTokenCluster> clusters = clusteror.cluster();
        List<LayoutTokenization> labeledTokenSequences = new ArrayList<LayoutTokenization>();
        LayoutTokenization currentTokenization = null;
        for (TaggingTokenCluster cluster : clusters) {
            if (cluster == null) {
                continue;
            }

            TaggingLabel clusterLabel = cluster.getTaggingLabel();
            List<LayoutToken> clusterTokens = cluster.concatTokens();

            if (inlineFullTextLabels.contains(clusterLabel)) {
                // sequence is not interrupted
                if (currentTokenization == null)
                    currentTokenization = new LayoutTokenization();

            } else {
                // we have an independent sequence
                if ((currentTokenization != null) && (currentTokenization.size() > 0)) {
                    labeledTokenSequences.add(currentTokenization);
                    currentTokenization = new LayoutTokenization();
                }
            }
            if (labels.contains(clusterLabel)) {
                if (currentTokenization == null)
                    currentTokenization = new LayoutTokenization();
                currentTokenization.addTokens(clusterTokens);
            }
        }

        if ((currentTokenization != null) && (currentTokenization.size() > 0))
            labeledTokenSequences.add(currentTokenization);

        return labeledTokenSequences;
    }

    /**
     * Processing Pdf files with the current models using new files located in a given directory.
     */

    public int processFullTextDirectory(String inputDirectory,
                                        String outputDirectory,
                                        boolean recursive,
                                        boolean saveAsset,
                                        boolean teiCoordinates,
                                        boolean segmentSentences,
                                        int ind) throws IOException {
        try {
            File path = new File(inputDirectory);
            if (!path.exists()) {
                throw new GrobidException("Cannot create training data because input directory can not be accessed: " + inputDirectory);
            }

            File pathOut = new File(outputDirectory);
            if (!pathOut.exists()) {
                throw new GrobidException("Cannot create training data because output directory can not be accessed: " + outputDirectory);
            }

            // we process all pdf files in the directory
            File[] refFiles = path.listFiles(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    System.out.println(name);
                    return name.endsWith(".pdf") || name.endsWith(".PDF");
                }
            });

            if (refFiles == null)
                return 0;

            System.out.println(refFiles.length + " files to be processed.");

            List<String> elementCoordinates = null;
            if (teiCoordinates) {
                elementCoordinates = Arrays.asList("figure", "persName", "s");
            }

            int n = 0;
            if (ind == -1) {
                // for undefined identifier (value at -1), we initialize it to 0
                n = 1;
            }

            for (final File file : refFiles) {
                try {
                    processing(file, outputDirectory, recursive, saveAsset, elementCoordinates, segmentSentences, n);
                } catch (final Exception exp) {
                    LOGGER.error("An error occurred while processing the following pdf: "
                        + file.getPath() + ": " + exp);
                }
                if (ind != -1)
                    n++;
            }

            return refFiles.length;
        } catch (final Exception exp) {
            throw new GrobidException("An exception occurred while running Grobid batch.", exp);
        }
    }

    /**
     * Process the content of the specified pdf and format the result as training data.
     *
     * @param inputFile          input file
     * @param outputFile         path to fulltext
     * @param recursive          recursive processing of files in the sub-directories (by default not recursive)
     * @param saveAsset          do not extract and save the PDF assets (bitmaps, vector graphics), by default the assets are extracted and saved
     * @param elementCoordinates the identified structures with coordinates in the original PDF, by default no coordinates are present
     * @param segmentSentences   add sentence segmentation level structures for paragraphs in the TEI XML result, by default no sentence segmentation is done
     * @param id                 id
     */
    public void processing(File inputFile,
                           String outputFile,
                           boolean recursive,
                           boolean saveAsset,
                           List<String> elementCoordinates,
                           boolean segmentSentences,
                           int id) {
        if (tmpPath == null) {
            throw new GrobidResourceException("Cannot process pdf file, because temp path is null.");
        }
        if (!tmpPath.exists()) {
            throw new GrobidResourceException("Cannot process pdf file, because temp path '" +
                tmpPath.getAbsolutePath() + "' does not exists.");
        }

        DocumentSource documentSource = null;
        Document doc = null;
        GrobidAnalysisConfig config = null;
        String result;

        try {
            if (!inputFile.exists()) {
                throw new GrobidResourceException("Cannot process the model, because the file '" +
                    inputFile.getAbsolutePath() + "' does not exists.");
            }
            String pdfFileName = inputFile.getName();

            File outputTEIFile = new File(outputFile + File.separator + pdfFileName.replace(".pdf", ".medical.tei.xml"));
            Writer writer = new OutputStreamWriter(new FileOutputStream(outputTEIFile, false), StandardCharsets.UTF_8);

            if (inputFile.getName().toLowerCase().endsWith(".pdf")) {
                System.out.println("Processing: " + inputFile.getPath());

                if (saveAsset) {
                    String baseName = pdfFileName.replace(".pdf", "").replace(".PDF", "");
                    String assetPath = outputFile + File.separator + baseName + "_assets";
                    config = GrobidAnalysisConfig.builder()
                        .pdfAssetPath(new File(assetPath))
                        .generateTeiCoordinates(elementCoordinates)
                        .withSentenceSegmentation(segmentSentences)
                        .build();
                } else
                    config = GrobidAnalysisConfig.builder()
                        .generateTeiCoordinates(elementCoordinates)
                        .withSentenceSegmentation(segmentSentences)
                        .build();
                result = parsers.getFullMedicalTextParser().processing(inputFile, config).getTei();

                StringBuilder tei = new StringBuilder();
                tei.append(result);
                writer.write(tei.toString());
                writer.close();
            } else if (recursive && inputFile.isDirectory()) {
                File[] newFiles = inputFile.listFiles();
                if (newFiles != null) {
                    for (final File file : newFiles) {
                        processing(file, outputFile, recursive, saveAsset, elementCoordinates, segmentSentences, id);
                    }
                }
            }
        } catch (Exception e) {
            throw new GrobidException("An exception occurred extracting medical reports.", e);
        } finally {
            DocumentSource.close(documentSource, true, true, true);
        }
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}