package org.bahmni.module.admin.csv;

import groovy.lang.GroovyClassLoader;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.bahmni.csv.EntityPersister;
import org.bahmni.csv.KeyValue;
import org.bahmni.csv.RowResult;
import org.bahmni.module.admin.csv.models.CSVPatientProgram;
import org.bahmni.module.admin.csv.models.MultipleEncounterRow;
import org.bahmni.module.admin.csv.patientmatchingalgorithm.BahmniPatientMatchingAlgorithm;
import org.bahmni.module.admin.csv.patientmatchingalgorithm.PatientMatchingAlgorithm;
import org.bahmni.module.admin.csv.patientmatchingalgorithm.exception.CannotMatchPatientException;
import org.bahmni.module.admin.encounter.BahmniEncounterTransactionImportService;
import org.bahmni.module.admin.observation.DiagnosisMapper;
import org.bahmni.module.admin.observation.ObservationMapper;
import org.bahmni.module.admin.retrospectiveEncounter.service.RetrospectiveEncounterTransactionService;
import org.bahmni.module.bahmnicore.service.BahmniPatientService;
import org.openmrs.Patient;
import org.openmrs.PatientProgram;
import org.openmrs.Program;
import org.openmrs.api.ConceptService;
import org.openmrs.api.EncounterService;
import org.openmrs.api.ProgramWorkflowService;
import org.openmrs.api.VisitService;
import org.openmrs.api.context.Context;
import org.openmrs.api.context.UserContext;
import org.openmrs.module.bahmniemrapi.encountertransaction.contract.BahmniEncounterTransaction;
import org.openmrs.module.bahmniemrapi.encountertransaction.service.BahmniEncounterTransactionService;
import org.openmrs.util.OpenmrsUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.util.List;

@Component
public class EncounterPersister implements EntityPersister<MultipleEncounterRow> {
    @Autowired
    private BahmniPatientService patientService;
    @Autowired
    private BahmniEncounterTransactionService bahmniEncounterTransactionService;
    @Autowired
    private ConceptService conceptService;
    @Autowired
    private EncounterService encounterService;
    @Autowired
    private VisitService visitService;
    @Autowired
    private ProgramWorkflowService programWorkflowService;

    private UserContext userContext;
    private String patientMatchingAlgorithmClassName;

    private static final Logger log = Logger.getLogger(EncounterPersister.class);
    public static final String PATIENT_MATCHING_ALGORITHM_DIRECTORY = "/patientMatchingAlgorithm/";
    protected DiagnosisMapper diagnosisMapper;

    public void init(UserContext userContext, String patientMatchingAlgorithmClassName) {
        this.userContext = userContext;
        this.patientMatchingAlgorithmClassName = patientMatchingAlgorithmClassName;

        // Diagnosis Service caches the diagnoses concept. Better if there is one instance of it for the one file import.
        diagnosisMapper = new DiagnosisMapper(conceptService);
    }

    @Override
    public RowResult<MultipleEncounterRow> validate(MultipleEncounterRow multipleEncounterRow) {
        return new RowResult<>(multipleEncounterRow);
    }

    @Override
    public RowResult<MultipleEncounterRow> persist(MultipleEncounterRow multipleEncounterRow) {
        // This validation is needed as patientservice get returns all patients for empty patient identifier
        if (StringUtils.isEmpty(multipleEncounterRow.patientIdentifier)) {
            return noMatchingPatients(multipleEncounterRow);
        }

        try {
            Context.openSession();
            Context.setUserContext(userContext);

            List<Patient> matchingPatients = patientService.get(multipleEncounterRow.patientIdentifier);
            Patient patient = matchPatients(matchingPatients, multipleEncounterRow.patientAttributes);
            if (patient == null) {
                return noMatchingPatients(multipleEncounterRow);
            }

            BahmniEncounterTransactionImportService encounterTransactionImportService =
                    new BahmniEncounterTransactionImportService(encounterService, new ObservationMapper(conceptService), diagnosisMapper);
            List<BahmniEncounterTransaction> bahmniEncounterTransactions = encounterTransactionImportService.getBahmniEncounterTransaction(multipleEncounterRow, patient);

            RetrospectiveEncounterTransactionService retrospectiveEncounterTransactionService =
                    new RetrospectiveEncounterTransactionService(bahmniEncounterTransactionService, visitService);

            for (BahmniEncounterTransaction bahmniEncounterTransaction : bahmniEncounterTransactions) {
                retrospectiveEncounterTransactionService.save(bahmniEncounterTransaction, patient);
            }

            for (CSVPatientProgram csvPatientProgram : multipleEncounterRow.getPatientPrograms()) {
                Program program = programWorkflowService.getProgramByName(csvPatientProgram.programName);

                List<PatientProgram> patientPrograms = programWorkflowService.getPatientPrograms(patient, program, null, null, null, null, false);
                if (patientPrograms != null && !patientPrograms.isEmpty()) {
                    PatientProgram existingProgram = patientPrograms.get(0);
                    throw new RuntimeException("Patient already enrolled in " + csvPatientProgram.programName + " from " + existingProgram.getDateEnrolled() + " to " + existingProgram.getDateCompleted());
                }

                PatientProgram patientProgram = new PatientProgram();
                patientProgram.setPatient(patient);
                patientProgram.setProgram(program);
                patientProgram.setDateEnrolled(csvPatientProgram.encounterDate);

                programWorkflowService.savePatientProgram(patientProgram);
            }

            return new RowResult<>(multipleEncounterRow);
        } catch (Exception e) {
            log.error(e);
            Context.clearSession();
            return new RowResult<>(multipleEncounterRow, e);
        } finally {
            Context.flushSession();
            Context.closeSession();
        }
    }

    private RowResult<MultipleEncounterRow> noMatchingPatients(MultipleEncounterRow multipleEncounterRow) {
        return new RowResult<>(multipleEncounterRow, "No matching patients found with ID:'" + multipleEncounterRow.patientIdentifier + "'");
    }

    private Patient matchPatients(List<Patient> matchingPatients, List<KeyValue> patientAttributes) throws IOException, IllegalAccessException, InstantiationException, CannotMatchPatientException {
        if (patientMatchingAlgorithmClassName == null) {
            Patient patient = new BahmniPatientMatchingAlgorithm().run(matchingPatients, patientAttributes);
            return patient;
        }
        Class clazz = new GroovyClassLoader().parseClass(new File(getAlgorithmClassPath()));
        PatientMatchingAlgorithm patientMatchingAlgorithm = (PatientMatchingAlgorithm) clazz.newInstance();
        log.debug("PatientMatching : Using Algorithm in " + patientMatchingAlgorithm.getClass().getName());
        return patientMatchingAlgorithm.run(matchingPatients, patientAttributes);
    }

    private String getAlgorithmClassPath() {
        return OpenmrsUtil.getApplicationDataDirectory() + PATIENT_MATCHING_ALGORITHM_DIRECTORY + patientMatchingAlgorithmClassName;
    }

}