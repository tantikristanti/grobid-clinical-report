package org.grobid.service;

/**
 * Interface contains path extensions for accessing the grobid-medical-report service.
 *
 */
public interface GrobidMedicalReportPaths {
    /**
     * root path
     */
    String ROOT = "/";

    /**
     * path extension for is alive request.
     */
    String PATH_IS_ALIVE = "isalive";

    /**
     * path extension for getting version
     */
    String PATH_GET_VERSION = "version";

    /**
     * path extension for getting description
     */
    String PATH_GET_DESCRIPTION = "grobidMedicalReport";


    /**
	 * path extension for processing document headers.
	 */
	String PATH_HEADER = "processHeaderDocument";

	/**
	 * path extension for processing document headers HTML.
	 */
	String PATH_HEADER_HTML = "processHeaderDocumentHTML";
	
	/**
	 * path extension for processing full text of documents.
	 */
	String PATH_FULL_TEXT = "processFulltextDocument";

	/**
	 * path extension for processing full text of documents together with image extraction.
	 */
	String PATH_FULL_TEXT_ASSET = "processFulltextAssetDocument";

	/**
	 * path extension for processing full text of documents.
	 */
	String PATH_FULL_TEXT_HTML = "processFulltextDocumentHTML";

	/**
	 * path extension for processing dates.
	 */
	String PATH_DATE = "processDate";

	/**
	 * path extension for processing names in header parts of documents headers.
	 */
	String PATH_HEADER_NAMES = "processHeaderNames";

	/**
	 * path extension for processing citation in patent documents in TEI.
	 */
	//String PATH_CITATION_PATENT_TEI = "processCitationPatentTEI";

	/**
	 * path extension for processing citation in patent documents in ST.36.
	 */
	String PATH_CITATION_PATENT_ST36 = "processCitationPatentST36";

	/**
	 * path extension for processing citation in patent documents in PDF.
	 */
	String PATH_CITATION_PATENT_PDF = "processCitationPatentPDF";

	/**
	 * path extension for processing citation in patent documents in utf-8 txt .
	 */
	String PATH_CITATION_PATENT_TXT = "processCitationPatentTXT";

	/**
	 * path extension for processing citation annotations.
	 */
	//String PATH_CITATION_ANNOTATION = "processCitationPatentTEI";

	/**
	 * path extension for processing names as appearing in a citation (e.g. bibliographic section).
	 */
	String PATH_CITE_NAMES = "processCitationNames";

	/**
	 * path extension for processing affiliation in document headers.
	 */
	String PATH_AFFILIATION = "processAffiliations";

	/**
	 * path extension for processing isolated citation.
	 */
	String PATH_CITATION = "processCitation";

	/**
	 * path extension for processing a list of isolated citations.
	 */
	String PATH_CITATION_LIST = "processCitationList";

	/**
	 * path extension for processing all the references in a PDF file.
	 */
	String PATH_REFERENCES = "processReferences";

	/**
	 * path extension for processing and annotating a PDF file.
	 */
	String PATH_PDF_ANNOTATION = "annotatePDF";

	/**
	 * path extension for the JSON annotations of the bibliographical references in a PDF file.
	 */
	String PATH_REFERENCES_PDF_ANNOTATION = "referenceAnnotations";

	/**
	 * path extension for the JSON annotations of the citations in a patent PDF file.
	 */
	String PATH_CITATIONS_PATENT_PDF_ANNOTATION = "citationPatentAnnotations";

	/**
	 * path extension for processing sha1.
	 */
	String PATH_SHA1 = "sha1";

	/**
	 * path extension for getting all properties.
	 */
	String PATH_ALL_PROPS = "allProperties";

	/**
	 * path extension to update property value.
	 */
	String PATH_CHANGE_PROPERTY_VALUE = "changePropertyValue";

	/**
	 * path to retrieve a model 
	 */
	String PATH_MODEL = "model";	

	/**
	 * path for launching the training of a model
	 */
	String PATH_MODEL_TRAINING = "modelTraining";		

	/**
	 * path for getting the advancement of the training of a model or 
	 * the evaluation metrics of the new model if the training is completed
	 */
	String PATH_TRAINING_RESULT = "trainingResult";		
}
