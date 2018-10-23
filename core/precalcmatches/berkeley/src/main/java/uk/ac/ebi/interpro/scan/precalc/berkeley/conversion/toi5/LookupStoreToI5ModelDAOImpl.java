package uk.ac.ebi.interpro.scan.precalc.berkeley.conversion.toi5;

import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.transaction.annotation.Transactional;
import uk.ac.ebi.interpro.scan.model.*;
import uk.ac.ebi.interpro.scan.model.SignatureLibrary;
import uk.ac.ebi.interpro.scan.precalc.berkeley.model.KVSequenceEntry;
import uk.ac.ebi.interpro.scan.precalc.berkeley.model.SimpleLookupMatch;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import java.util.*;

/**
 * @author Phil Jones, EMBL-EBI
 * @version $Id$
 * @since 1.0
 */

public class LookupStoreToI5ModelDAOImpl implements LookupStoreToI5ModelDAO {

    private static final Logger LOGGER = Logger.getLogger(LookupStoreToI5ModelDAOImpl.class.getName());

    private Map<SignatureLibrary, LookupMatchConverter> signatureLibraryToMatchConverter;

    protected EntityManager entityManager;

    @PersistenceContext
    protected void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Required
    public void setSignatureLibraryToMatchConverter(Map<SignatureLibrary, LookupMatchConverter> signatureLibraryToMatchConverter) {
        this.signatureLibraryToMatchConverter = signatureLibraryToMatchConverter;
    }

    /**
     * Method to store matches based upon lookup from the Berkeley match database of precalculated matches.
     *
     * @param nonPersistedProtein being a newly instantiated Protein object
     * @param berkeleyMatches     being a Set of BerkeleyMatch objects, retrieved / unmarshalled from
     *                            the Berkeley Match web service.
     */
    @Transactional(readOnly = true)
    public void populateProteinMatches(Protein nonPersistedProtein, List<KVSequenceEntry> kvSequenceEntries, Map<String, SignatureLibraryRelease> analysisJobMap) {
        populateProteinMatches(Collections.singleton(nonPersistedProtein), kvSequenceEntries,analysisJobMap);
    }

    @Transactional(readOnly = true)
    public void populateProteinMatches(Set<Protein> preCalculatedProteins, List<KVSequenceEntry> kvSequenceEntries, Map<String, SignatureLibraryRelease> analysisJobMap) {
        String debugString = "";
        final Map<String, Protein> md5ToProteinMap = new HashMap<String, Protein>(preCalculatedProteins.size());
        // Populate the lookup map.
        for (Protein protein : preCalculatedProteins) {
            md5ToProteinMap.put(protein.getMd5().toUpperCase(), protein);
        }

        //the following was the problem:
        //analysisJobMap = new HashMap<String, SignatureLibraryRelease>();
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("analysisJobMap: " + analysisJobMap);
        }
        //Mapping between SignatureLibrary and the version number, e.g key=PIRSF,value=2.84
        Map<SignatureLibrary, String> librariesToAnalyse = null;

        //Populate map with data
        if (analysisJobMap != null) {
            librariesToAnalyse = new HashMap<>();
            for (String analysisJobName : analysisJobMap.keySet()) {
                String analysisJob = null;
                String versionNumber = null;
                if (analysisJobName != null) {
                    analysisJob = analysisJobName;
                    versionNumber = analysisJobMap.get(analysisJobName).getVersion();

                    debugString = "Job: " + analysisJobName + " :- analysisJob: " + analysisJob + " versionNumber: " + versionNumber;
//                    Utilities.verboseLog(10, debugString);
                    LOGGER.debug(debugString);
                } else {
                    throw new IllegalStateException("Analysis job name is in an unexpected format: " + analysisJobName);
                }
                final SignatureLibrary matchingLibrary = SignatureLibraryLookup.lookupSignatureLibrary(analysisJobName);
                if (matchingLibrary != null) {
                    librariesToAnalyse.put(matchingLibrary, versionNumber);
                }
            }
        }
        //Debug
        if (LOGGER.isDebugEnabled()) {
            StringBuilder jobsToAnalyse = new StringBuilder();
            for (String job : analysisJobMap.keySet()) {
                jobsToAnalyse.append("job: " + job + " version: " + analysisJobMap.get(job).getVersion() + "\n");
            }
            LOGGER.debug("From analysisJobMap" + jobsToAnalyse);
            jobsToAnalyse = new StringBuilder();
            for (SignatureLibrary signatureLibrary : librariesToAnalyse.keySet()) {
                jobsToAnalyse.append("job: " + signatureLibrary.getName() + " version: " + librariesToAnalyse.get(signatureLibrary) + "\n");
            }
            LOGGER.debug("From librariesToAnalyse: " + jobsToAnalyse);

//        LOGGER.debug("From librariesToAnalyse: " + jobsToAnalyse);
        }

        // Collection of BerkeleyMatches of different kinds.
        for (KVSequenceEntry lookupMatch : kvSequenceEntries) {
            //now we ahave a list
            Set<String> sequenceHits = lookupMatch.getSequenceHits();
            for (String sequenceHit :sequenceHits) {
                SimpleLookupMatch simpleMatch = new SimpleLookupMatch(sequenceHit);
                String signatureLibraryReleaseVersion = simpleMatch.getSigLibRelease();
                final SignatureLibrary sigLib = SignatureLibraryLookup.lookupSignatureLibrary(simpleMatch.getSignatureLibraryName());
                //Quick Hack: deal with CDD and SFLD for now as they need to be calculated locally (since sites are not in Berkeley DB yet)
                if (sigLib.getName().equals(SignatureLibrary.CDD.getName())
                        || sigLib.getName().equals(SignatureLibrary.SFLD.getName())
                        || sigLib.getName().equals(SignatureLibrary.SIGNALP_EUK.getName())
                        || sigLib.getName().equals(SignatureLibrary.SIGNALP_GRAM_POSITIVE.getName())
                        || sigLib.getName().equals(SignatureLibrary.SIGNALP_GRAM_NEGATIVE.getName())) {
                    continue;
                }
                if (LOGGER.isDebugEnabled() && analysisJobMap.containsKey(sigLib.getName().toUpperCase())) {
                    LOGGER.debug("Found Library : sigLib: " + sigLib + " version: " + signatureLibraryReleaseVersion);
                }
                debugString = "sigLib: " + sigLib + "version: " + signatureLibraryReleaseVersion;
                debugString += "\n librariesToAnalyse value: " + librariesToAnalyse.keySet().toString() + " version: " + librariesToAnalyse.get(sigLib);
//            Utilities.verboseLog(10, debugString);

                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("sigLib: " + sigLib + "version: " + signatureLibraryReleaseVersion);
                    LOGGER.debug("librariesToAnalyse value: " + librariesToAnalyse.keySet().toString() + " version: " + librariesToAnalyse.get(sigLib));
                }

                // Check to see if the signature library is required for the analysis.
                // First check: librariesToAnalyse == null -> -appl option hasn't been set
                // Second check: Analysis library has been request with the right release version -> -appl PIRSF-2.84
                if (librariesToAnalyse == null || (librariesToAnalyse.containsKey(sigLib) && librariesToAnalyse.get(sigLib).equals(signatureLibraryReleaseVersion))) {
                    // Retrieve Signature to match
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("Check match for : " + sigLib + "-" + signatureLibraryReleaseVersion);
                    }
                    Query sigQuery = entityManager.createQuery("select distinct s from Signature s where s.accession = :sig_ac and s.signatureLibraryRelease.library = :library and s.signatureLibraryRelease.version = :version");
                    sigQuery.setParameter("sig_ac", simpleMatch.getSignatureAccession());
                    sigQuery.setParameter("library", sigLib);

                    sigQuery.setParameter("version", signatureLibraryReleaseVersion);

                    @SuppressWarnings("unchecked") List<Signature> signatures = sigQuery.getResultList();
                    Signature signature = null;
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("signatures size: " + signatures.size());
                    }

                    //what should be the behaviour here:
                    //
                    if (signatures.size() == 0) {   // This Signature is not in I5, so cannot store this one.
                        continue;
                    } else if (signatures.size() > 1) {
                        //try continue instead of exiting
                        String warning = "Data inconsistency issue. This distribution appears to contain the same signature multiple times: "
                                + " signature: " + simpleMatch.getSignatureAccession()
                                + " library name: " + simpleMatch.getSignatureLibraryName()
                                //+ " match id: " + simpleMatch.getMatchId()
                                + " sequence md5: " + simpleMatch.getProteinMD5();
                        LOGGER.warn(warning);
                        continue;
                        //throw new IllegalStateException("Data inconsistency issue. This distribution appears to contain the same signature multiple times: " + berkeleyMatch.getSignatureAccession());
                    } else {
                        signature = signatures.get(0);
                        if (LOGGER.isDebugEnabled()) {
                            LOGGER.debug("signatures size: " + signatures.get(0));
                        }
                    }

                    // determine the type or the match currently being observed
                    // Retrieve the appropriate converter to turn the BerkeleyMatch into an I5 match
                    // Type is based upon the member database type.

                    if (signatureLibraryToMatchConverter == null) {
                        throw new IllegalStateException("The match converter map has not been populated.");
                    }
                    if (sigLib.getName().equals(SignatureLibrary.PFAM.getName()) ||
                            sigLib.getName().equals(SignatureLibrary.PIRSF.getName()) ||
                            sigLib.getName().equals(SignatureLibrary.GENE3D.getName()) ||
                            sigLib.getName().equals(SignatureLibrary.TIGRFAM.getName())) {
                        LookupMatchConverter matchConverter = signatureLibraryToMatchConverter.get(sigLib);
                        if (matchConverter != null) {
                            Match i5Match = matchConverter.convertMatch(simpleMatch, signature);
                            if (i5Match != null) {
                                // Lookup up the right protein
                                final Protein prot = md5ToProteinMap.get(simpleMatch.getProteinMD5().toUpperCase());
                                if (prot != null) {
                                    prot.addMatch(i5Match);
                                } else {
                                    LOGGER.warn("Attempted to store a match in a Protein, but cannot find the protein??? This makes no sense. Possible coding error.");
                                }
                            }
                        } else {
                            LOGGER.warn("Unable to persist match " + simpleMatch + " as there is no available conversion for signature libarary " + sigLib);
                        }
                    }
                }
            }
        }
    }
}