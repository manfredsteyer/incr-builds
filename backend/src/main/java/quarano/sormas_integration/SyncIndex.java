package quarano.sormas_integration;

import io.vavr.Tuple2;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import quarano.account.Department;
import quarano.account.DepartmentProperties;
import quarano.account.DepartmentRepository;
import quarano.core.Address;
import quarano.core.EmailAddress;
import quarano.core.PhoneNumber;
import quarano.core.ZipCode;
import quarano.core.web.MapperWrapper;
import quarano.department.CaseType;
import quarano.department.TrackedCase;
import quarano.department.TrackedCaseRepository;
import quarano.sormas_integration.backlog.IndexSyncBacklogRepository;
import quarano.sormas_integration.indexcase.SormasCase;
import quarano.sormas_integration.indexcase.SormasCasePerson;
import quarano.sormas_integration.lookup.SormasLookup;
import quarano.sormas_integration.lookup.SormasLookupRepository;
import quarano.sormas_integration.mapping.SormasCaseDto;
import quarano.sormas_integration.mapping.SormasCaseMapper;
import quarano.sormas_integration.mapping.SormasPersonDto;
import quarano.sormas_integration.mapping.SormasPersonMapper;
import quarano.sormas_integration.person.SormasPerson;
import quarano.sormas_integration.report.ContactsSyncReport;
import quarano.sormas_integration.report.IndexSyncReport;
import quarano.sormas_integration.report.IndexSyncReportRepository;
import quarano.tracking.TrackedPerson;
import quarano.tracking.TrackedPersonRepository;
import quarano.tracking.web.TrackedPersonDto;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * @author Federico Grasso
 */

@Slf4j
@Data
@Setter(AccessLevel.NONE)
@RequiredArgsConstructor
@Component
public class SyncIndex {

    private @Getter @Setter(value = AccessLevel.PACKAGE) String cronSchedule;
    private final @NonNull MapperWrapper mapper;
    private final @NonNull TrackedCaseRepository trackedCases;
    private final @NonNull TrackedPersonRepository trackedPersons;
    private final @NonNull SormasLookupRepository lookup;
    private final @NonNull DepartmentRepository departments;
    private final @NonNull IndexSyncReportRepository reports;
    private final @NonNull IndexSyncBacklogRepository backlog;
    private HashMap<String, TrackedPerson> personsFromQuarano = new HashMap<>();
    private HashMap<String, Department> departmentsFromQuarano = new HashMap<>();
    private final @NonNull SormasIntegrationProperties properties;
    private final @NonNull DepartmentProperties departmentProperties;

    @Scheduled(cron="${quarano.sormas-synch.interval.indexcases:-}")
    public void syncIndexCases() {
        if(StringUtils.isNotBlank(properties.getSormasurl())){
            log.info("Index cases synchronization started [V1]");
            log.info("MASTER: " + properties.getMaster().getIndexcases());

            long executionTimeSpan = System.currentTimeMillis();

            IndexSyncReport.ReportStatus executionStatus = IndexSyncReport.ReportStatus.STARTED;

            log.debug("Creating report instance...");
            IndexSyncReport newReport = new IndexSyncReport(
                    UUID.randomUUID(),
                    String.valueOf(0),
                    String.valueOf(0),
                    LocalDateTime.now(),
                    String.valueOf(0),
                    String.valueOf(executionStatus)
            );
            log.debug("Report instance created");

            // Retrieving reports number...
            long reportsCount = reports.count();

            try{
                SormasClient sormasClient = new SormasClient(
                        properties.getSormasurl(),
                        properties.getSormasuser(),
                        properties.getSormaspass()
                );

                log.debug("Getting last report...");
                List<IndexSyncReport> report = reports.getOrderBySyncDateDesc();

                LocalDateTime since = LocalDateTime.of(2020, 1, 1, 0, 0);

                // if reports table is not empty...
                if(!report.isEmpty()){
                    IndexSyncReport singleReport = report.get(0);
                    log.debug("Report list is not empty");

                    // if is already present an active report quit current synchronization
                    if(singleReport.getStatus().equals(String.valueOf(IndexSyncReport.ReportStatus.STARTED))){
                        executionStatus = IndexSyncReport.ReportStatus.FAILED;
                        log.warn("Another schedule is already running... ABORTED");
                        return;
                    }

                    List<IndexSyncReport> lastSuccessfulReport = reports.getSuccessfulOrderBySyncDateDesc();

                    if(!lastSuccessfulReport.isEmpty()){
                        since = lastSuccessfulReport.get(0).getSyncDate();
                    }
                }

                log.debug("Last sync: " + since);

                // Save current synchronization entry
                reports.save(newReport);

                // if master is sormas...
                if(properties.getMaster().getIndexcases().equals("sormas")) {
                    syncCasesFromSormas(sormasClient, since, newReport, executionTimeSpan);
                }
                else if(properties.getMaster().getIndexcases().equals("quarano")) {
                    // if reports table is empty
                    if(reportsCount == 0){
                        // start an initial synchronization
                        initialSynchFromQuarano(sormasClient, newReport);
                    }
                    // else
                    else{
                        // start a standard synchronization
                        syncCasesFromQuarano(sormasClient, newReport.getSyncDate(), newReport);
                    }
                }

                // Save report with success status
                executionStatus = IndexSyncReport.ReportStatus.SUCCESS;
            }
            catch(Exception ex){
                log.error(ex.getMessage(), ex);
                // Save report with failed status
                executionStatus = IndexSyncReport.ReportStatus.FAILED;
            }
            finally {
                updateReport(newReport, executionTimeSpan, executionStatus);
            }
        }
        else{
            log.warn("Sormas URL not present: NON-INTEGRATED MODE");
        }
    }

    private void syncCasesFromSormas(SormasClient sormasClient, LocalDateTime since, IndexSyncReport newReport, long executionTimeSpan) throws WebClientResponseException {
        personsFromQuarano.clear();
        departmentsFromQuarano.clear();

        Mono<List<SormasPerson>> persons = sormasClient.getPersons(since).collectList();
        Mono<List<SormasCase>> cases = sormasClient.getCases(since).collectList();

        Mono.zip(cases, persons).flatMap(data -> {
            /*** FETCH ALL CASES THAT HAS BEEN CHANGED ***/
            List<SormasCase> casesResponse = data.getT1();
            /*** FETCH ALL PERSONS THAT HAS BEEN CHANGED ***/
            List<SormasPerson> personsResponse = data.getT2();

            List<SormasPerson> remainingPersons = data.getT2();

            newReport.setCasesNumber(String.valueOf(casesResponse.size()));
            newReport.setPersonsNumber(String.valueOf(personsResponse.size()));

            log.debug(casesResponse.size() + " cases to handle");
            log.debug(personsResponse.size() + " persons to handle");

            /*** for each case... ***/
            for(int i = 0; i < casesResponse.size(); i++){
                // current case
                SormasCase sormasCase = casesResponse.get(i);
                log.debug("Case UUID: " + sormasCase.getUuid());
                // person of current case
                SormasCasePerson casePerson = sormasCase.getPerson();

                /*** Fill lookup table with new Sormas Person-Case association ***/
                Optional<SormasLookup> existingLookup = lookup.findByPerson(casePerson.getUuid());

                if(existingLookup.isEmpty()){
                    lookup.save(new SormasLookup(casePerson.getUuid(), sormasCase.getUuid()));
                }

                /*** Check if person already exists inside Quarano system ***/
                TrackedPerson personFromQuarano = getPersonFromQuarano(casePerson.getUuid());

                /*** if exists... ***/
                if(personFromQuarano != null){
                    log.debug("Case person already exists");
                    // Update TrackedPerson

                    SormasPerson personRelatedToCase = personsResponse.stream()
                            .filter(person ->
                                    casePerson.getUuid().equals(person.getUuid()) &&
                                            (
                                                    (person.getEmailAddress() != null && !person.getEmailAddress().equals(""))
                                                            || (person.getPhone() != null && !person.getPhone().equals(""))
                                            )
                            )
                            .findFirst()
                            .orElse(null);

                    log.debug("Updating tracked person...");

                    if(personRelatedToCase != null){
                        personFromQuarano = updatePerson(personFromQuarano, personRelatedToCase);
                        trackedPersons.save(personFromQuarano);
                        log.debug("Tracked person updated");

                        log.debug("Retrieving person cases...");
                        Optional<TrackedCase> personCase = trackedCases.findByTrackedPerson(personFromQuarano.getId());
                        log.debug("Person cases retrieved");

                        /***
                         * If TrackedPerson has TrackedCase of CaseType INDEX
                         * Transform ContactCase to IndexCase
                         ***/
                        if(personCase.isPresent()){
                            TrackedCase modifiedCase = personCase.get();
                            modifiedCase.setType(CaseType.INDEX);
                            trackedCases.save(modifiedCase);
                            log.debug("Case related to person saved to INDEX");
                        }

                        remainingPersons.remove(personRelatedToCase);
                    }
                }
                /*** else... ***/
                else {
                    /*** Find according person from Sormas person response ***/
                    SormasPerson personRelatedToCase = personsResponse.stream()
                            .filter(person ->
                                    casePerson.getUuid().equals(person.getUuid()) &&
                                            (
                                                    StringUtils.isNotEmpty(person.getEmailAddress()) ||
                                                            StringUtils.isNotEmpty(person.getPhone())
                                            )
                            )
                            .findFirst()//.findAny()
                            .orElse(null);

                    if (personRelatedToCase != null) {
                        /*** Store new TrackedCase with type INDEX and new TrackedPerson ***/
                        log.debug("Case person is new on Quarano");
                        TrackedPersonDto personDto = mapper.map(SormasPersonMapper.INSTANCE.map(personRelatedToCase), TrackedPersonDto.class);
                        TrackedPerson trackedPerson = mapper.map(personDto, TrackedPerson.class);

                        if(sormasCase.getDistrict() != null){
                            // Multi tenant approach will not be considered because is not used in production
                            // all cases will be created for the department that is configured inside environment variable
                            // Department department = getDepartment(sormasCase.getDistrict().getCaption());

                            String rkiCode = departmentProperties.getDefaultDepartment().getRkiCode();

                            Optional<Department> department = departments.findByRkiCode(rkiCode);

                            if(department.isPresent()){
                                SormasCaseDto caseDto = mapper.map(SormasCaseMapper.INSTANCE.map(sormasCase), SormasCaseDto.class);
                                TrackedCase trackedCase = mapper.map(caseDto, new TrackedCase(trackedPerson, CaseType.INDEX, department.get()));
                                trackedCases.save(trackedCase);
                                log.debug("Case saved");
                            }
                            else{
                                log.error("Department with RkiCode" + rkiCode + "was not found");
                            }
                        }
                    }
                    else{
                        log.warn("Person related to case " + sormasCase.getUuid() + "does not have an Email or is non present in persons response");
                    }

                    remainingPersons.remove(personRelatedToCase);
                }
            }
            /*** Handle Persons not handled above ***/
            for(int i = 0; i < remainingPersons.size(); i++){
                SormasPerson person = remainingPersons.get(i);

                TrackedPerson personFromQuarano = getPersonFromQuarano(person.getUuid());

                if(personFromQuarano != null){
                    personFromQuarano = updatePerson(personFromQuarano, person);
                    trackedPersons.save(personFromQuarano);
                    log.debug("Tracked person updated");
                }
                else{
                    /*** Check in Sormas if exists a Case ***/

                    Optional<SormasLookup> lookupQuery = lookup.findByPerson(person.getUuid());

                    if(lookupQuery.isPresent()){

                        List<String> uuids = new ArrayList<String>();

                        uuids.add(lookupQuery.get().getCaseId());

                        sormasClient.getCasesById(uuids).collectList()
                        .flatMap(response -> {
                            if(!response.isEmpty()){
                                SormasCase sormasCase = response.get(0);
                                /*** Store new TrackedCase with type INDEX and new TrackedPerson ***/
                                if((person.getEmailAddress() != null && !person.getEmailAddress().equals(""))
                                        || (person.getPhone() != null && !person.getPhone().equals(""))){

                                    log.debug("Case person is new on Quarano");
                                    TrackedPersonDto personDto = mapper.map(SormasPersonMapper.INSTANCE.map(person), TrackedPersonDto.class);
                                    TrackedPerson trackedPerson = mapper.map(personDto, TrackedPerson.class);

                                    if(sormasCase.getDistrict() != null){
                                        // Multi tenant approach will not be considered because is not used in production
                                        // all cases will be created for the department that is configured inside environment variable
                                        // Department department = getDepartment(sormasCase.getDistrict().getCaption());

                                        String rkiCode = departmentProperties.getDefaultDepartment().getRkiCode();

                                        Optional<Department> department = departments.findByRkiCode(rkiCode);

                                        if(department.isPresent()){
                                            SormasCaseDto caseDto = mapper.map(SormasCaseMapper.INSTANCE.map(sormasCase), SormasCaseDto.class);
                                            TrackedCase trackedCase = mapper.map(caseDto, new TrackedCase(trackedPerson, CaseType.INDEX, department.get()));
                                            trackedCases.save(trackedCase);
                                            log.debug("Case saved");
                                        }
                                        else{
                                            log.error("Department with RkiCode" + rkiCode + "was not found");
                                        }
                                    }
                                }
                            }
                            return null;
                        }).onErrorMap(ex -> {
                            // Save report with failed status
                            log.error(ex.getMessage(), ex);
                            updateReport(newReport, executionTimeSpan, IndexSyncReport.ReportStatus.FAILED);
                            return ex;
                        }).subscribe();
                    }
                }
            }

            /***
             * Update since value
             ***/

            updateReport(newReport, executionTimeSpan, IndexSyncReport.ReportStatus.SUCCESS);

            return Mono.empty();
        }).onErrorMap(ex -> {
            // Save report with failed status
            log.error(ex.getMessage(), ex);
            updateReport(newReport, executionTimeSpan, IndexSyncReport.ReportStatus.FAILED);
            return ex;
        }).subscribe();
    }

    private Department getDepartment(String name){
        Department department = getDepartmentFromQuarano(name);

        if(department == null){
            log.warn("Department" + name + "not exists");
            department = new Department(name);
            departments.save(department);
            log.debug("Department saved");
        }

        return department;
    }

    private TrackedPerson getPersonFromQuarano(String uuid){
        TrackedPerson trackedPerson = personsFromQuarano.get(uuid);

        if(trackedPerson == null){
            Optional<TrackedPerson> existingPerson = trackedPersons.findBySormasUuid(uuid);

            if(existingPerson.isPresent()){
                trackedPerson = existingPerson.get();
                personsFromQuarano.put(uuid, trackedPerson);
                return trackedPerson;
            }
        }

        return trackedPerson;
    }

    private Department getDepartmentFromQuarano(String name){
        Department department = departmentsFromQuarano.get(name);

        if(department == null){
            log.debug("Check district " + name);
            Optional<Department> departmentQuery = departments.findByName(name);

            if(departmentQuery.isPresent()){
                department = departmentQuery.get();
                departmentsFromQuarano.put(name, department);
                return department;
            }
        }

        return department;
    }

    private TrackedPerson updatePerson(TrackedPerson trackedPerson, SormasPerson sormasPerson){

        if(sormasPerson.getFirstName() != null && sormasPerson.getLastName() != null){
            trackedPerson.setFirstName(sormasPerson.getFirstName());
            trackedPerson.setLastName(sormasPerson.getLastName());
        }

        if(StringUtils.isNotBlank(sormasPerson.getEmailAddress())){
            trackedPerson.setEmailAddress(EmailAddress.of(sormasPerson.getEmailAddress()));
        }

        if(StringUtils.isNotBlank(sormasPerson.getPhone())){
            trackedPerson.setPhoneNumber(PhoneNumber.of(sormasPerson.getPhone()));
            trackedPerson.setMobilePhoneNumber(PhoneNumber.of(sormasPerson.getPhone()));
        }

        if(
                sormasPerson.getBirthdateYYYY() != null &&
                        sormasPerson.getBirthdateMM() != null &&
                        sormasPerson.getBirthdateDD() != null
        ) {
            trackedPerson.setDateOfBirth(LocalDate.of(
                    sormasPerson.getBirthdateYYYY(),
                    sormasPerson.getBirthdateMM(),
                    sormasPerson.getBirthdateDD()
            ));
        }

        try{
            trackedPerson.setAddress(new Address(
                    StringUtils.isNoneBlank(sormasPerson.getAddress().getStreet()) ? sormasPerson.getAddress().getStreet() : null,
                    Address.HouseNumber.NONE,
                    StringUtils.isNoneBlank(sormasPerson.getAddress().getCity()) ? sormasPerson.getAddress().getCity() : null,
                    StringUtils.isNoneBlank(sormasPerson.getAddress().getPostalCode()) ? ZipCode.of(sormasPerson.getAddress().getPostalCode()) : null
            ));
        }
        catch (IllegalArgumentException ex){
            log.warn("Illegal Address");
        }

        return trackedPerson;
    }

    // Initial synchronization from Quarano
    private void initialSynchFromQuarano(SormasClient sormasClient, IndexSyncReport newReport) {

        // Get first tracked persons page
        Page<TrackedPerson> personsPage = trackedPersons.findAll(PageRequest.of(0, 1000));

        int pages = personsPage.getTotalPages();

        Integer newReportPersonsCount = 0;
        Integer newReportCasesCount = 0;

        // for every page...
        for(int i = 0; i < pages; i++){
            List<TrackedPerson> persons = personsPage.stream().collect(Collectors.toList());

            List<TrackedPerson> indexPersons = new ArrayList<>();
            List<Tuple2<TrackedCase, TrackedPerson>> indexCases = new ArrayList<>();

            // for every tracked person...
            for(int j = 0; j < persons.size(); j++){
                TrackedPerson trackedPerson = persons.get(j);

                newReportPersonsCount++;

                // Search Tracked Case related to person
                Optional<TrackedCase> trackedCaseQuery = trackedCases.findByTrackedPerson(trackedPerson);

                if(trackedCaseQuery.isPresent()){
                    TrackedCase trackedCase = trackedCaseQuery.get();

                    // if case is of type INDEX
                    if(trackedCase.isIndexCase()){

                        newReportCasesCount++;

                        // synchronize person
                        indexPersons.add(trackedPerson);
                        indexCases.add(new Tuple2<>(trackedCase, trackedPerson));
                    }
                }
            }

            synchronizePersons(sormasClient, indexPersons);
            synchronizeCases(sormasClient, indexCases);

            if(i + 1 < pages){
                personsPage = trackedPersons.findAll(PageRequest.of(i + 1, 1000));
            }
        }

        newReport.setPersonsNumber(newReportPersonsCount.toString());
        newReport.setCasesNumber(newReportCasesCount.toString());
    }

    private void syncCasesFromQuarano(SormasClient sormasClient, LocalDateTime synchDate, IndexSyncReport newReport) {
        // Determine IDs to sync
        List<UUID> entities = backlog.findBySyncDate(synchDate);

        List<TrackedPerson> indexPersons = new ArrayList<>();
        List<Tuple2<TrackedCase, TrackedPerson>> indexCases = new ArrayList<>();

        Integer newReportPersonsCount = 0;
        Integer newReportCasesCount = 0;

        for(int i = 0; i < entities.size(); i++){
            UUID entity = entities.get(i);

            // Fetch person from Database
            Optional<TrackedPerson> trackedPersonQuery = trackedPersons.findById(TrackedPerson.TrackedPersonIdentifier.of(entity));

            if(trackedPersonQuery.isPresent()){

                TrackedPerson trackedPerson = trackedPersonQuery.get();
                newReportPersonsCount++;

                // Search Tracked Case related to person
                Optional<TrackedCase> trackedCaseQuery = trackedCases.findByTrackedPerson(trackedPerson);

                if(trackedCaseQuery.isPresent()){

                    TrackedCase trackedCase = trackedCaseQuery.get();
                    // if case is of type INDEX
                    if(trackedCase.isIndexCase()){

                        newReportCasesCount++;
                        indexPersons.add(trackedPerson);
                        indexCases.add(new Tuple2<>(trackedCase, trackedPerson));
                    }
                }
            }
        }

        // synchronize persons
        List<TrackedPerson> successPersons = synchronizePersons(sormasClient, indexPersons);
        // synchronize cases
        synchronizeCases(sormasClient, indexCases);

        successPersons.forEach(person -> {
            // Delete from backlog
            backlog.deleteAfterSynchronization(UUID.fromString(person.getId().toString()), synchDate);
        });

        newReport.setPersonsNumber(newReportPersonsCount.toString());
        newReport.setCasesNumber(newReportCasesCount.toString());
    }

    private List<TrackedPerson> synchronizePersons(SormasClient sormasClient, List<TrackedPerson> persons){
        List<SormasPerson> sormasPersons = new ArrayList<>();
        List<TrackedPerson> successPersons = new ArrayList<>();

        persons.forEach(person -> {
            if(StringUtils.isBlank(person.getSormasUuid())){
                // Create Sormas ID
                person.setSormasUuid(UUID.randomUUID().toString());
                trackedPersons.save(person);
            }

            // Map TrackedPerson to SormasPerson
            SormasPersonDto personDto = mapper.map(person, SormasPersonDto.class);
            SormasPerson sormasPerson = SormasPersonMapper.INSTANCE.map(personDto);

            sormasPersons.add(sormasPerson);
        });

        // Push to Sormas
        String[] response = sormasClient.postPersons(sormasPersons).block();

        for(int i = 0; i < response.length; i++){
            log.debug("Person number" + i + ": " + response[i]);
            if(response[i].equals("OK")){
                successPersons.add(persons.get(i));
            }
        }

        return successPersons;
    }

    private void synchronizeCases(SormasClient sormasClient, List<Tuple2<TrackedCase, TrackedPerson>> indexCases){
        List<SormasCase> sormasCases = new ArrayList<>();

        indexCases.forEach(caze -> {
            if(StringUtils.isBlank(caze._1.getSormasUuid())){
                // Create Sormas ID
                caze._1.setSormasUuid(UUID.randomUUID().toString());
            }

            // Map TrackedCase to SormasCase
            SormasCase sormasCase = SormasCaseMapper.INSTANCE.map(
                    caze._1,
                    caze._2,
                    properties.getReportingUser(),
                    properties.getDistrict(),
                    properties.getRegion(),
                    properties.getHealthFacility()
            );

            sormasCases.add(sormasCase);
        });

        // Push to Sormas
        String[] response = sormasClient.postCases(sormasCases).block();

        for(int i = 0; i < response.length; i++){
            log.debug("Case number" + i + ": " + response[i]);
        }
    }

    private void updateReport(IndexSyncReport report, long executionTimeSpan, IndexSyncReport.ReportStatus status){
        report.setSyncTime(String.valueOf((System.currentTimeMillis() - executionTimeSpan) / 1000));
        report.setStatus(String.valueOf(status));
        reports.save(report);
        log.info("Report saved with status " + status);
    }
}
