/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.api.index;

import java.util.EnumMap;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

import org.neo4j.helpers.TimeUtil;
import org.neo4j.logging.Log;
import org.neo4j.util.FeatureToggles;

public class LoggingPhaseTracker implements PhaseTracker
{

    private static final int PERIOD_INTERVAL = FeatureToggles.getInteger( LoggingPhaseTracker.class, "period_interval", 600 );
    private static final String MESSAGE_PREFIX = "TIME/PHASE ";

    private final long periodIntervalInSeconds;
    private final Log log;

    private EnumMap<Phase,Logger> times = new EnumMap<>( Phase.class );
    private Phase currentPhase;
    private long timeEnterPhase;
    private boolean stopped;
    private long lastPeriodReport = -1;

    LoggingPhaseTracker( Log log )
    {
        this( PERIOD_INTERVAL, log );
    }

    LoggingPhaseTracker( long periodIntervalInSeconds, Log log )
    {
        this.periodIntervalInSeconds = periodIntervalInSeconds;
        this.log = log;
        for ( Phase phase : Phase.values() )
        {
            times.put( phase, new Logger( phase ) );
        }
    }

    @Override
    public void enterPhase( Phase phase )
    {
        if ( stopped )
        {
            throw new IllegalStateException( "Trying to report a new phase after phase tracker has been stopped." );
        }
        if ( phase != currentPhase )
        {
            long now = logCurrentTime();
            currentPhase = phase;
            timeEnterPhase = now;

            if ( lastPeriodReport == -1 )
            {
                lastPeriodReport = now;
            }

            long secondsSinceLastPeriodReport = TimeUnit.NANOSECONDS.toSeconds( now - lastPeriodReport );
            if ( secondsSinceLastPeriodReport >= periodIntervalInSeconds )
            {
                // Report period
                periodReport( secondsSinceLastPeriodReport );
                lastPeriodReport = now;
            }
        }
    }

    @Override
    public void stop()
    {
        stopped = true;
        logCurrentTime();
        currentPhase = null;
        finalReport();
    }

    EnumMap<Phase,Logger> times()
    {
        return times;
    }

    private void finalReport()
    {
        log.debug( MESSAGE_PREFIX + mainReportString( "Final" ) );
    }

    private void periodReport( long secondsSinceLastPeriodReport )
    {
        String periodReportString = periodReportString( secondsSinceLastPeriodReport );
        String mainReportString = mainReportString( "Total" );
        log.debug( MESSAGE_PREFIX + mainReportString + ", " + periodReportString );
    }

    private String mainReportString( String title )
    {
        StringJoiner joiner = new StringJoiner( ", ", title + ": ", "" );
        times.values().forEach( p -> joiner.add( p.toString() ) );
        return joiner.toString();
    }

    private String periodReportString( long secondsSinceLastPeriodReport )
    {
        StringJoiner joiner = new StringJoiner( ", ", "Last " + secondsSinceLastPeriodReport + " sec: ", "" );
        times.values().forEach( logger ->
        {
            joiner.add( logger.period().toString() );
            logger.period().reset();
        } );
        return joiner.toString();
    }

    private long logCurrentTime()
    {
        long now = System.nanoTime();
        if ( currentPhase != null )
        {
            Logger logger = times.get( currentPhase );
            long nanoTime = now - timeEnterPhase;
            logger.log( nanoTime );
        }
        return now;
    }

    public class Logger extends Counter
    {
        final Counter periodCounter;

        private Logger( Phase phase )
        {
            super( phase );
            periodCounter = new Counter( phase );
            periodCounter.reset();
        }

        void log( long nanoTime )
        {
            super.log( nanoTime );
            periodCounter.log( nanoTime );
        }

        Counter period()
        {
            return periodCounter;
        }
    }

    public class Counter
    {
        private final Phase phase;
        long totalTime;
        long nbrOfReports;
        long maxTime;
        long minTime;

        Counter( Phase phase )
        {
            this.phase = phase;
        }

        void log( long nanoTime )
        {
            totalTime += nanoTime;
            nbrOfReports++;
            maxTime = Math.max( maxTime, nanoTime );
            minTime = Math.min( minTime, nanoTime );
        }

        void reset()
        {
            totalTime = 0;
            nbrOfReports = 0;
            maxTime = Long.MIN_VALUE;
            minTime = Long.MAX_VALUE;
        }

        @Override
        public String toString()
        {
            StringJoiner joiner = new StringJoiner( ", ", phase.toString() + "[", "]" );
            if ( nbrOfReports == 0 )
            {
                addToString( "nbrOfReports", nbrOfReports, joiner, false );
            }
            else
            {
                long avgTime = totalTime / nbrOfReports;
                addToString( "totalTime", totalTime, joiner, true );
                addToString( "avgTime", avgTime, joiner, true );
                addToString( "minTime", minTime, joiner, true );
                addToString( "maxTime", maxTime, joiner, true );
                addToString( "nbrOfReports", nbrOfReports, joiner, false );
            }
            return joiner.toString();
        }

        void addToString( String name, long measurement, StringJoiner joiner, boolean isTime )
        {
            String measurementString;
            if ( isTime )
            {
                long timeRoundedToMillis = TimeUnit.MILLISECONDS.toNanos( TimeUnit.NANOSECONDS.toMillis( measurement ) );
                measurementString = TimeUtil.nanosToString( timeRoundedToMillis );
            }
            else
            {
                measurementString = Long.toString( measurement );
            }
            joiner.add( String.format( "%s=%s", name, measurementString ) );
        }
    }
}