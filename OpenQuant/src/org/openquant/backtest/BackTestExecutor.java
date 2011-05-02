package org.openquant.backtest;

/*
 Copyright (c) 2010, Jay Logelin
 All rights reserved.

 Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following 
 conditions are met:

 Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
 Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer 
 in the documentation and/or other materials provided with the distribution.  Neither the name of the JQuant nor the names of its 
 contributors may be used to endorse or promote products derived from this software without specific prior written permission.
 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, 
 BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT 
 SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL 
 DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS 
 INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE 
 OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openquant.backtest.report.AbstractReport;
import org.openquant.backtest.report.JFreeChartReport;
import org.openquant.data.SeriesDatasource;
import org.openquant.util.Day;
import org.springframework.util.StopWatch;

public class BackTestExecutor {

	private Log log = LogFactory.getLog(BackTestExecutor.class);

	private SeriesDatasource data;

	private List<String> symbols;

	private CandleSeriesTestContext test;

	private double capital = 100000;

	private double commission = 9.99;

	private double slippage = 0.001;

	private List<Position> positions = new ArrayList<Position>();

	private String reportName;

	public BackTestExecutor(SeriesDatasource data, List<String> symbols,
			CandleSeriesTestContext test, String reportName, double capital,
			double commission, double slippage) {
		super();
		this.data = data;
		this.symbols = symbols;
		this.test = test;
		this.capital = capital;
		this.commission = commission;
		this.slippage = slippage;
		this.reportName = reportName;
	}

	public double run() {

		StopWatch watch = new StopWatch("-- DEBUGGING --");
		watch.start("execute tradesystem");

		for (String symbol : symbols) {

			CandleSeries candleSeries;
			try {
				candleSeries = data.fetchSeries(symbol);
				test.reset();
				test.setSeries(candleSeries);
				test.run();

				positions.addAll(test.getOrderManager().getClosedPositions());
			} catch (Exception e) {
				log.error(e.getMessage(), e);
			}

		}

		// order all of the positions by entry date
		Collections.sort(positions, new Comparator<Position>() {

			@Override
			public int compare(Position positionOne, Position positionTwo) {
				return positionOne.getEntryDate().compareTo(
						positionTwo.getEntryDate());
			}

		});

		// perform trade system level filtering
		List<Position> filteredPositions = filterRiskManagementStrategy();

		// generate reports
		AbstractReport report = new JFreeChartReport(reportName + ".jpg",
				capital, commission, slippage, filteredPositions, test
						.getOrderManager().getOpenPositions());
		double endingCapital = report.getTotalCapitalAndEquity();
		log.debug(String.format("Ending capital is %12.2f", endingCapital));
		report.render();

		watch.stop();
		log.debug(String.format("Time : %s seconds", watch.getTotalTimeSeconds()));

		return endingCapital;
	}

	/*
	 * Default risk management strategy involves using all possible positions
	 * for a day, until there is no more available capital.
	 */

	private List<Position> filterRiskManagementStrategy() {

		List<Position> filteredPositions = new ArrayList<Position>();

		if (positions.size() < 1) {
			return positions;
		}

		Position previous = positions.get(0);

		List<Position> daysPositions = new ArrayList<Position>();
		daysPositions.add(previous);

		Double cap = new Double(this.capital);

		for (int i = 1; i < positions.size(); i++) {
			Position current = positions.get(i);
			if (Day.compare(current.getEntryDate(), previous.getEntryDate()) != 0) {
				// different day

				// order all of the day's positions by score
				Collections.sort(daysPositions, new Comparator<Position>() {

					@Override
					public int compare(Position positionOne,
							Position positionTwo) {
						return new Double(positionOne.getScore())
								.compareTo(positionTwo.getScore());
					}

				});

				DaySummary summary = defaultRiskManagement(daysPositions, cap);
				cap = cap + summary.getProfit();

				filteredPositions.addAll(summary.getPositions());
				daysPositions.clear();
			}

			daysPositions.add(current);
			previous = current;

		}

		// log.info(String.format("Ending capital is %12.2f", cap));

		// test.finish(cap);

		return filteredPositions;

	}

	class DaySummary {
		private double profit;
		private List<Position> positions;

		public DaySummary(double profit, List<Position> positions) {
			super();
			this.setProfit(profit);
			this.setPositions(positions);
		}

		public void setProfit(double profit) {
			this.profit = profit;
		}

		public double getProfit() {
			return profit;
		}

		public void setPositions(List<Position> positions) {
			this.positions = positions;
		}

		public List<Position> getPositions() {
			return positions;
		}

	}

	private DaySummary defaultRiskManagement(List<Position> daysPositions,
			Double cap) {

		List<Position> rList = new ArrayList<Position>();
		double totalDailyProfit = 0.0;

		for (Position current : daysPositions) {
			double entryCost = calculateEntryCost(current);
			if (entryCost < cap) {

				rList.add(current);
				cap -= entryCost;
				// calculate running profit
				totalDailyProfit += calculateProfit(current);
			}
		}

		return new DaySummary(totalDailyProfit, rList);
	}

	private double calculateEntryCost(Position position) {
		return position.getEntryPrice() * position.getQuantity();
	}

	private double calculateProfit(Position position) {
		double profit = (position.getExitPrice() - position.getEntryPrice())
				* position.getQuantity();
		return profit - (profit * slippage) - commission;
	}

	private List<Position> doTradeSystemFilter() {

		List<Position> filteredPositions = new ArrayList<Position>();

		Position previous = positions.get(0);

		List<Position> daysPositions = new ArrayList<Position>();
		daysPositions.add(previous);

		for (int i = 1; i < positions.size(); i++) {
			Position current = positions.get(i);
			if (Day.compare(current.getEntryDate(), previous.getEntryDate()) != 0) {
				// different day
				filteredPositions.addAll(test.preFilterOrders(daysPositions));
				daysPositions.clear();
			}

			daysPositions.add(current);
			previous = current;

		}

		return filteredPositions;

	}

	private double calculateEquity(Collection<Position> closedPositions,
			Collection<Position> openPositions) {

		EquityCalculator calculator = new EquityCalculator(capital, commission,
				slippage);

		for (Position position : closedPositions) {
			calculator.processClosePosition(position);
		}

		for (Position position : openPositions) {
			calculator.processOpenPosition(position);
		}

		calculator.finish();
		return calculator.getCapital();

	}

}