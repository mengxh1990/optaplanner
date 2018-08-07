/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.examples.flightcrewscheduling.persistence;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.optaplanner.core.api.score.constraint.ConstraintMatch;
import org.optaplanner.core.api.score.constraint.ConstraintMatchTotal;
import org.optaplanner.examples.common.persistence.AbstractXlsxSolutionFileIO;
import org.optaplanner.examples.flightcrewscheduling.app.FlightCrewSchedulingApp;
import org.optaplanner.examples.flightcrewscheduling.domain.Airport;
import org.optaplanner.examples.flightcrewscheduling.domain.Employee;
import org.optaplanner.examples.flightcrewscheduling.domain.Flight;
import org.optaplanner.examples.flightcrewscheduling.domain.FlightAssignment;
import org.optaplanner.examples.flightcrewscheduling.domain.FlightCrewParametrization;
import org.optaplanner.examples.flightcrewscheduling.domain.FlightCrewSolution;
import org.optaplanner.examples.flightcrewscheduling.domain.Skill;

import static java.util.stream.Collectors.*;
import static org.optaplanner.examples.flightcrewscheduling.domain.FlightCrewParametrization.*;

public class FlightCrewSchedulingXlsxFileIO extends AbstractXlsxSolutionFileIO<FlightCrewSolution> {

    @Override
    public FlightCrewSolution read(File inputSolutionFile) {
        try (InputStream in = new BufferedInputStream(new FileInputStream(inputSolutionFile))) {
            XSSFWorkbook workbook = new XSSFWorkbook(in);
            return new FlightCrewSchedulingXlsxReader(workbook).read();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed reading inputSolutionFile ("
                    + inputSolutionFile + ").", e);
        }
    }

    private static class FlightCrewSchedulingXlsxReader extends AbstractXlsxReader<FlightCrewSolution> {

        private Map<String, Skill> skillMap;
        private Map<String, Airport> airportMap;

        public FlightCrewSchedulingXlsxReader(XSSFWorkbook workbook) {
            super(workbook);
        }

        @Override
        public FlightCrewSolution read() {
            solution = new FlightCrewSolution();
            readConfiguration();
            readSkillList();
            readAirportList();
            readTaxiTimeMaps();
            readEmployeeList();
            readFlightListAndFlightAssignmentList();
            return solution;
        }

        private void readConfiguration() {
            nextSheet("Configuration");
            nextRow(false);
            readHeaderCell("Constraint");
            readHeaderCell("Weight");
            readHeaderCell("Description");
            FlightCrewParametrization parametrization = new FlightCrewParametrization();
            parametrization.setId(0L);
            readIntConstraintLine(NIGHTS_AWAY_FROM_BASE_FAIRNESS, parametrization::setNightsAwayFromBaseFairness,
                    "Soft penalty to load balance the nights away from base");
            readIntConstraintLine(REQUIRED_SKILL, null,
                    "Hard penalty per missing required skill");
            solution.setParametrization(parametrization);
        }

        private void readSkillList() {
            nextSheet("Skills");
            nextRow(false);
            readHeaderCell("Name");
            List<Skill> skillList = new ArrayList<>(currentSheet.getLastRowNum() - 1);
            skillMap = new HashMap<>(currentSheet.getLastRowNum() - 1);
            long id = 0L;
            while (nextRow()) {
                Skill skill = new Skill();
                skill.setId(id++);
                skill.setName(nextStringCell().getStringCellValue());
                skillMap.put(skill.getName(), skill);
                skillList.add(skill);
            }
            solution.setSkillList(skillList);
        }

        private void readAirportList() {
            nextSheet("Airports");
            nextRow(false);
            readHeaderCell("Code");
            readHeaderCell("Name");
            readHeaderCell("Latitude");
            readHeaderCell("Longitude");
            List<Airport> airportList = new ArrayList<>(currentSheet.getLastRowNum() - 1);
            airportMap = new HashMap<>(currentSheet.getLastRowNum() - 1);
            long id = 0L;
            while (nextRow()) {
                Airport airport = new Airport();
                airport.setId(id++);
                airport.setCode(nextStringCell().getStringCellValue());
                airport.setName(nextStringCell().getStringCellValue());
                airport.setLatitude(nextNumericCell().getNumericCellValue());
                airport.setLongitude(nextNumericCell().getNumericCellValue());
                airportMap.put(airport.getCode(), airport);
                airportList.add(airport);
            }
            solution.setAirportList(airportList);
        }

        private void readTaxiTimeMaps() {
            nextSheet("Taxi time");
            nextRow();
            readHeaderCell("Driving time in minutes by taxi between two nearby airports to allow employees to start from a different airport.");
            List<Airport> airportList = solution.getAirportList();
            nextRow();
            readHeaderCell("Airport code");
            for (Airport airport : airportList) {
                readHeaderCell(airport.getCode());
            }
            for (Airport a : airportList) {
                a.setTaxiTimeInMinutesMap(new LinkedHashMap<>(airportList.size()));
                nextRow();
                readHeaderCell(a.getCode());
                for (Airport b : airportList) {
                    XSSFCell taxiTimeCell = nextNumericCellOrBlank();
                    if (taxiTimeCell != null) {
                        a.getTaxiTimeInMinutesMap().put(b, (long) taxiTimeCell.getNumericCellValue());
                    }
                }
            }
        }

        private void readEmployeeList() {
            nextSheet("Employees");
            nextRow(false);
            readHeaderCell("");
            readHeaderCell("");
            readHeaderCell("");
//            readTimeslotDaysHeaders();
            nextRow(false);
            readHeaderCell("Name");
            readHeaderCell("Home airport");
            readHeaderCell("Skills");
//            readTimeslotHoursHeaders();
            List<Employee> employeeList = new ArrayList<>(currentSheet.getLastRowNum() - 1);
            long id = 0L;
            while (nextRow()) {
                Employee employee = new Employee();
                employee.setId(id++);
                employee.setName(nextStringCell().getStringCellValue());
                if (!VALID_NAME_PATTERN.matcher(employee.getName()).matches()) {
                    throw new IllegalStateException(currentPosition() + ": The employee name (" + employee.getName()
                            + ") must match to the regular expression (" + VALID_NAME_PATTERN + ").");
                }
                String homeAirportCode = nextStringCell().getStringCellValue();
                Airport homeAirport = airportMap.get(homeAirportCode);
                if (homeAirport == null) {
                    throw new IllegalStateException(currentPosition()
                            + ": The employee (" + employee.getName()
                            + ")'s homeAirport (" + homeAirportCode
                            + ") does not exist in the airports (" + airportMap.keySet()
                            + ") of the other sheet (Airports).");
                }
                employee.setHomeAirport(homeAirport);
                String[] skillNames = nextStringCell().getStringCellValue().split(", ");
                Set<Skill> skillSet = new LinkedHashSet<>(skillNames.length);
                for (String skillName : skillNames) {
                    Skill skill = skillMap.get(skillName);
                    if (skill == null) {
                        throw new IllegalStateException(currentPosition()
                                + ": The employee (" + employee + ")'s skill (" + skillName
                                + ") does not exist in the skills (" + skillMap.keySet()
                                + ") of the other sheet (Skills).");
                    }
                    skillSet.add(skill);
                }
                employee.setSkillSet(skillSet);
                employeeList.add(employee);
            }
            solution.setEmployeeList(employeeList);
        }

        private void readFlightListAndFlightAssignmentList() {
            nextSheet("Flights");
            nextRow(false);
            readHeaderCell("Flight number");
            readHeaderCell("Departure airport code");
            readHeaderCell("Departure UTC date time");
            readHeaderCell("Arrival airport code");
            readHeaderCell("Arrival UTC date time");
            readHeaderCell("Employee skill requirements");
            List<Flight> flightList = new ArrayList<>(currentSheet.getLastRowNum() - 1);
            List<FlightAssignment> flightAssignmentList = new ArrayList<>((currentSheet.getLastRowNum() - 1) * 5);
            long id = 0L;
            long flightAssignmentId = 0L;
            while (nextRow()) {
                Flight flight = new Flight();
                flight.setId(id++);
                flight.setFlightNumber(nextStringCell().getStringCellValue());
                String departureAirportCode = nextStringCell().getStringCellValue();
                Airport departureAirport = airportMap.get(departureAirportCode);
                if (departureAirport == null) {
                    throw new IllegalStateException(currentPosition()
                            + ": The flight (" + flight.getFlightNumber()
                            + ")'s departureAirport (" + departureAirportCode
                            + ") does not exist in the airports (" + airportMap.keySet()
                            + ") of the other sheet (Airports).");
                }
                flight.setDepartureAirport(departureAirport);
                flight.setDepartureUTCDateTime(LocalDateTime.parse(nextStringCell().getStringCellValue(), DATE_TIME_FORMATTER));
                String arrivalAirportCode = nextStringCell().getStringCellValue();
                Airport arrivalAirport = airportMap.get(arrivalAirportCode);
                if (arrivalAirport == null) {
                    throw new IllegalStateException(currentPosition()
                            + ": The flight (" + flight.getFlightNumber()
                            + ")'s arrivalAirport (" + arrivalAirportCode
                            + ") does not exist in the airports (" + airportMap.keySet()
                            + ") of the other sheet (Airports).");
                }
                flight.setArrivalAirport(arrivalAirport);
                flight.setArrivalUTCDateTime(LocalDateTime.parse(nextStringCell().getStringCellValue(), DATE_TIME_FORMATTER));

                String[] skillNames = nextStringCell().getStringCellValue().split(", ");
                for (int i = 0; i < skillNames.length; i++) {
                    FlightAssignment flightAssignment = new FlightAssignment();
                    flightAssignment.setId(flightAssignmentId++);
                    flightAssignment.setFlight(flight);
                    flightAssignment.setIndexInFlight(i);
                    Skill requiredSkill = skillMap.get(skillNames[i]);
                    if (requiredSkill == null) {
                        throw new IllegalStateException(currentPosition()
                                + ": The flight (" + flight.getFlightNumber()
                                + ")'s requiredSkill (" + requiredSkill
                                + ") does not exist in the skills (" + skillMap.keySet()
                                + ") of the other sheet (Skills).");
                    }
                    flightAssignment.setRequiredSkill(requiredSkill);
                    flightAssignmentList.add(flightAssignment);
                }
                flightList.add(flight);
            }
            solution.setFlightList(flightList);
            solution.setFlightAssignmentList(flightAssignmentList);
        }

    }


    @Override
    public void write(FlightCrewSolution solution, File outputSolutionFile) {
        try (FileOutputStream out = new FileOutputStream(outputSolutionFile)) {
            Workbook workbook = new FlightCrewSchedulingXlsxWriter(solution).write();
            workbook.write(out);
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Failed writing outputSolutionFile ("
                    + outputSolutionFile + ") for solution (" + solution + ").", e);
        }
    }

    private static class FlightCrewSchedulingXlsxWriter extends AbstractXlsxWriter<FlightCrewSolution> {

        public FlightCrewSchedulingXlsxWriter(FlightCrewSolution solution) {
            super(solution, FlightCrewSchedulingApp.SOLVER_CONFIG);
        }

        @Override
        public Workbook write() {
            writeSetup();
            writeConfiguration();
            writeSkillList();
            writeAirportList();
            writeTaxiTimeMaps();
            writeEmployeeList();
            writeFlightListAndFlightAssignmentList();
            writeScoreView();
            return workbook;
        }

        private void writeConfiguration() {
            nextSheet("Configuration", 1, 1, false);
            nextRow();
            nextHeaderCell("Constraint");
            nextHeaderCell("Weight");
            nextHeaderCell("Description");
            FlightCrewParametrization parametrization = solution.getParametrization();

            writeIntConstraintLine(NIGHTS_AWAY_FROM_BASE_FAIRNESS, parametrization::getNightsAwayFromBaseFairness,
                    "Soft penalty to load balance the nights away from base");
            nextRow();
            writeIntConstraintLine(REQUIRED_SKILL, null,
                    "Hard penalty per missing required skill");
            autoSizeColumnsWithHeader();
        }

        private void writeSkillList() {
            nextSheet("Skills", 1, 1, false);
            nextRow();
            nextHeaderCell("Name");
            for (Skill skill : solution.getSkillList()) {
                nextRow();
                nextCell().setCellValue(skill.getName());
            }
            autoSizeColumnsWithHeader();
        }

        private void writeAirportList() {
            nextSheet("Airports", 1, 1, false);
            nextRow();
            nextHeaderCell("Code");
            nextHeaderCell("Name");
            nextHeaderCell("Latitude");
            nextHeaderCell("Longitude");
            for (Airport airport : solution.getAirportList()) {
                nextRow();
                nextCell().setCellValue(airport.getCode());
                nextCell().setCellValue(airport.getName());
                nextCell().setCellValue(airport.getLatitude());
                nextCell().setCellValue(airport.getLongitude());
            }
            autoSizeColumnsWithHeader();
        }

        private void writeTaxiTimeMaps() {
            nextSheet("Taxi time", 2, 3, false);
            nextRow();
            nextHeaderCell("Driving time in minutes by taxi between two nearby airports to allow employees to start from a different airport.");
            currentSheet.addMergedRegion(new CellRangeAddress(currentRowNumber, currentRowNumber,
                    currentColumnNumber, currentColumnNumber + 10));
            List<Airport> airportList = solution.getAirportList();
            nextRow();
            nextHeaderCell("Airport code");
            for (Airport airport : airportList) {
                nextHeaderCell(airport.getCode());
            }
            for (Airport a : airportList) {
                nextRow();
                nextHeaderCell(a.getCode());
                for (Airport b : airportList) {
                    Long taxiTime = a.getTaxiTimeInMinutesTo(b);
                    if (taxiTime == null) {
                        nextCell();
                    } else {
                        nextCell().setCellValue(taxiTime);
                    }
                }
            }
            autoSizeColumnsWithHeader();
        }

        private void writeEmployeeList() {
            nextSheet("Employees", 1, 2, false);
            nextRow();
            nextHeaderCell("");
            nextHeaderCell("");
            nextHeaderCell("");
//            writeTimeslotDaysHeaders();
            nextRow();
            nextHeaderCell("Name");
            nextHeaderCell("Home airport");
            nextHeaderCell("Skills");
//            writeTimeslotHoursHeaders();
            for (Employee employee : solution.getEmployeeList()) {
                nextRow();
                nextCell().setCellValue(employee.getName());
                nextCell().setCellValue(employee.getHomeAirport().getCode());
                nextCell().setCellValue(String.join(", ", employee.getSkillSet().stream().map(Skill::getName).collect(toList())));
            }
            autoSizeColumnsWithHeader();
        }

        private void writeFlightListAndFlightAssignmentList() {
            nextSheet("Flights", 1, 1, false);
            nextRow();
            nextHeaderCell("Flight number");
            nextHeaderCell("Departure airport code");
            nextHeaderCell("Departure UTC date time");
            nextHeaderCell("Arrival airport code");
            nextHeaderCell("Arrival UTC date time");
            nextHeaderCell("Employee skill requirements");
            Map<Flight, List<FlightAssignment>> flightToFlightAssignmentMap = solution.getFlightAssignmentList()
                    .stream().collect(groupingBy(FlightAssignment::getFlight, toList()));
            for (Flight flight : solution.getFlightList()) {
                nextRow();
                nextCell().setCellValue(flight.getFlightNumber());
                nextCell().setCellValue(flight.getDepartureAirport().getCode());
                nextCell().setCellValue(DATE_TIME_FORMATTER.format(flight.getDepartureUTCDateTime()));
                nextCell().setCellValue(flight.getArrivalAirport().getCode());
                nextCell().setCellValue(DATE_TIME_FORMATTER.format(flight.getArrivalUTCDateTime()));
                nextCell().setCellValue(flightToFlightAssignmentMap.get(flight).stream()
                        .map(FlightAssignment::getRequiredSkill).map(Skill::getName)
                        .collect(joining(", ")));
            }
            autoSizeColumnsWithHeader();
        }

        private void writeScoreView() {
            nextSheet("Score view", 1, 3, true);
            nextRow();
            nextHeaderCell("Score");
            nextCell().setCellValue(solution.getScore() == null ? "Not yet solved" : solution.getScore().toShortString());
            nextRow();
            nextRow();
            nextHeaderCell("Constraint match");
            nextHeaderCell("Match score");
            nextHeaderCell("Total score");
            for (ConstraintMatchTotal constraintMatchTotal : constraintMatchTotalList) {
                nextRow();
                nextHeaderCell(constraintMatchTotal.getConstraintName());
                nextCell();
                nextCell().setCellValue(constraintMatchTotal.getScore().toShortString());
                List<ConstraintMatch> constraintMatchList = new ArrayList<>(constraintMatchTotal.getConstraintMatchSet());
                constraintMatchList.sort(Comparator.comparing(ConstraintMatch::getScore));
                for (ConstraintMatch constraintMatch : constraintMatchList) {
                    nextRow();
                    nextCell().setCellValue("    " + constraintMatch.getJustificationList().stream()
                            .filter(o -> o instanceof FlightAssignment).map(o -> ((FlightAssignment) o).getFlight().toString())
                            .collect(joining(", ")));
                    nextCell().setCellValue(constraintMatch.getScore().toShortString());
                    nextCell();
                    nextCell();
                }
            }
            autoSizeColumnsWithHeader();
        }

    }

}