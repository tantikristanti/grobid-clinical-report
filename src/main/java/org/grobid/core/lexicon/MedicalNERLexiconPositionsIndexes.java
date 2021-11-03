package org.grobid.core.lexicon;

import org.apache.commons.collections4.CollectionUtils;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.utilities.OffsetPosition;

import java.util.ArrayList;
import java.util.List;

public class MedicalNERLexiconPositionsIndexes {

    private Lexicon lexicon;

    private List<OffsetPosition> localLocationPositions = new ArrayList<OffsetPosition>();
    private List<OffsetPosition> localPersonTitlePositions = new ArrayList<OffsetPosition>();
    private List<OffsetPosition> localOrganisationPositions = new ArrayList<OffsetPosition>();
    private List<OffsetPosition> localOrgFormPositions = new ArrayList<OffsetPosition>();

    public MedicalNERLexiconPositionsIndexes(MedicalNERLexicon medicalNERLexicon) {
        this.lexicon = lexicon;
    }

    public void computeIndexes(List<LayoutToken> tokens) {
        localLocationPositions = lexicon.tokenPositionsLocationNames(tokens);
        localPersonTitlePositions = lexicon.tokenPositionsPersonTitle(tokens);
        localOrganisationPositions = lexicon.tokenPositionsOrganisationNames(tokens);
        localOrgFormPositions = lexicon.tokenPositionsOrgForm(tokens);
    }

    /**
     * return true if the token position are within the boundaries of a lexicon token.
     * It assumes the scan is done incrementally.
     * It updates the currentPosition to avoid iterating through the lexicon positions.
     */
    public static boolean isTokenInLexicon(List<OffsetPosition> listPositionInLexicon, int currentPosition) {
        if (CollectionUtils.isNotEmpty(listPositionInLexicon)) {
            for (int mm = 0; mm < listPositionInLexicon.size(); mm++) {
                if ((currentPosition >= listPositionInLexicon.get(mm).start) &&
                        (currentPosition <= listPositionInLexicon.get(mm).end)) {
                    return true;
                } else if (currentPosition < listPositionInLexicon.get(mm).start) {
                    return false;
                } else if (currentPosition > listPositionInLexicon.get(mm).end) {
                    continue;
                }
            }
        }
        return false;
    }

    public List<OffsetPosition> getLocalLocationPositions() {
        return localLocationPositions;
    }

    public void setLocalLocationPositions(List<OffsetPosition> localLocationPositions) {
        this.localLocationPositions = localLocationPositions;
    }

    public List<OffsetPosition> getLocalPersonTitlePositions() {
        return localPersonTitlePositions;
    }

    public void setLocalPersonTitlePositions(List<OffsetPosition> localPersonTitlePositions) {
        this.localPersonTitlePositions = localPersonTitlePositions;
    }

    public List<OffsetPosition> getLocalOrganisationPositions() {
        return localOrganisationPositions;
    }

    public void setLocalOrganisationPositions(List<OffsetPosition> localOrganisationPositions) {
        this.localOrganisationPositions = localOrganisationPositions;
    }

    public List<OffsetPosition> getLocalOrgFormPositions() {
        return localOrgFormPositions;
    }

    public void setLocalOrgFormPositions(List<OffsetPosition> localOrgFormPositions) {
        this.localOrgFormPositions = localOrgFormPositions;
    }
}
