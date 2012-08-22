package updaters;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import com.google.gson.Gson;

import play.Play;

import models.GtfsFeed;
import models.MetroArea;
import models.NtdAgency;

/**
 * A deployment plan contains all the information needed to build a graph for a given metro area at a
 * given time.
 * 
 * @author mattwigway
 */

public class DeploymentPlan {
	private Date startDate;
	private Date endDate;
	private MetroArea area;
	private static TimeZone gmt = TimeZone.getTimeZone("GMT");
	private SimpleDateFormat isoDate;
	private Calendar calendar;
	private FeedDescriptor[] feeds;
	
	/**
	 * Create a plan for the given metro at the current time and for the default window.
	 * @param area
	 */
	public DeploymentPlan(MetroArea area) {
		this(area, new Date(112, 7, 15));
	}
	
	/**
	 * Create a plan for the given metro at the given date and for the default window.
	 * @param area
	 * @param date
	 */
	public DeploymentPlan(MetroArea area, Date date) {
		this(area, date, 90);
				//Integer.parseInt(Play.configuration.getProperty("dashboard.planwindow", "14")));
	}
	
	/**
	 * Create a new deployment plan.
	 * @param area The Metro Area to create a plan for.
	 * @param date The date this plan should go into effect
	 * @param window The number of days this plan should attempt to find valid trip plans.
	 */
	public DeploymentPlan(MetroArea area, Date date, int window) {
		this.area = area;
		this.calendar = Calendar.getInstance(gmt);
		this.isoDate = new SimpleDateFormat("yyyy-MM-dd");
		this.startDate = date;
		calendar.setTime(date);
		calendar.add(Calendar.DAY_OF_YEAR, window);
		this.endDate = calendar.getTime();
		
		Set<FeedDescriptor> toInclude = new HashSet<FeedDescriptor>();
		
		for (NtdAgency agency : area.getAgencies()) {
			// all the unsuperseded feeds for this agency
			
			for (GtfsFeed feed : agency.feeds) {
				if (feed.supersededBy != null) {
					continue;
				}
				
				addFeeds(feed, toInclude);
			}
			
			this.feeds = new FeedDescriptor[toInclude.size()];
			this.feeds = toInclude.toArray(this.feeds);
		}
	}
	
	/**
	 * Follows the chain of supersession back until it adds enough feeds to cover the window. 
	 * @param feed
	 * @param toInclude
	 */
	private void addFeeds(GtfsFeed feed, Set<FeedDescriptor> toInclude) {
		addFeeds(feed, toInclude, 0);
	}
	
	private void addFeeds(GtfsFeed feed, Set<FeedDescriptor> toInclude, int iteration) {
		FeedDescriptor fd;
		GtfsFeed olderFeed;
		
		if (this.startDate.compareTo(feed.startDate) < 0) {
			// Feed starts after today
			
			if (this.endDate.compareTo(feed.startDate) > 0) {
				// Feed starts before the end of the window, include it
				fd = new FeedDescriptor();
				fd.feedId = feed.storedId;
				
				// force expire before the next one starts, if it's not already
				if (feed.supersededBy != null && 
						feed.expirationDate.compareTo(feed.supersededBy.startDate) >= 0) {
					calendar.setTime(feed.supersededBy.startDate);
					// Go back 12 hours, this should be yesterday since we have date at 00:00:00
					calendar.add(Calendar.HOUR, -12);
					fd.expireOn = isoDate.format(calendar.getTime());
				}
				// otherwise, let it expire normally
				else {
					fd.expireOn = isoDate.format(feed.expirationDate);
				}
				
				fd.defaultAgencyId = feed.dataExchangeId + "_" + iteration;
				toInclude.add(fd);
			}
			
			olderFeed = GtfsFeed.find("bySupersededBy", feed).first();
			if (olderFeed == null) {
				// Data doesn't go back far enough
				return;
			}
			
			addFeeds(olderFeed, toInclude, iteration + 1);
			return;
		}
		else {
			// Feed does not start after today. Presumably we'll be following feeds
			// through supersession in roughly chronological order, so we don't need
			// to continue searching for older feeds.
			fd = new FeedDescriptor();
			fd.feedId = feed.storedId;
			fd.expireOn = isoDate.format(feed.expirationDate);
			fd.defaultAgencyId = feed.dataExchangeId + "_" + iteration;
			
			// force expire if necessary
			if (feed.supersededBy != null &&
					feed.expirationDate.compareTo(feed.supersededBy.startDate) >= 0) {
				calendar.setTime(feed.supersededBy.startDate);
				// Go back 12 hours, this should be yesterday since we have date at 00:00:00
				calendar.add(Calendar.HOUR, -12);
				fd.expireOn = isoDate.format(calendar.getTime());
			}
			
			toInclude.add(fd);
			return;
		}
	}
	
	/**
	 * Convert this plan to JSON for Deployer.
	 * @return JSON for Deployer.
	 */
	public String toJson () {
		DeploymentPlanProxy plan = new DeploymentPlanProxy();
		plan.feeds = this.feeds;
		plan.metroId = this.area.id;
		plan.metro = this.area.name;
		
		Gson gson = new Gson();
		return gson.toJson(plan);
	}
	
	// This is serialized to JSON and sent to deployer.
	private class DeploymentPlanProxy {
		private long metroId;
		private String metro;
		private FeedDescriptor[] feeds;
	}
	
	public class FeedDescriptor {
		public String getFeedId() {
			return feedId;
		}
		public String getExpireOn() {
			return expireOn;
		}
		public String getDefaultAgencyId() {
			return defaultAgencyId;
		}
		private String feedId;
		private String expireOn;
		private String defaultAgencyId;
		
		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + getOuterType().hashCode();
			result = prime
					* result
					+ ((defaultAgencyId == null) ? 0 : defaultAgencyId
							.hashCode());
			result = prime * result
					+ ((expireOn == null) ? 0 : expireOn.hashCode());
			result = prime * result
					+ ((feedId == null) ? 0 : feedId.hashCode());
			return result;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			FeedDescriptor other = (FeedDescriptor) obj;
			if (!getOuterType().equals(other.getOuterType()))
				return false;
			if (defaultAgencyId == null) {
				if (other.defaultAgencyId != null)
					return false;
			} else if (!defaultAgencyId.equals(other.defaultAgencyId))
				return false;
			if (expireOn == null) {
				if (other.expireOn != null)
					return false;
			} else if (!expireOn.equals(other.expireOn))
				return false;
			if (feedId == null) {
				if (other.feedId != null)
					return false;
			} else if (!feedId.equals(other.feedId))
				return false;
			return true;
		}
		private DeploymentPlan getOuterType() {
			return DeploymentPlan.this;
		}
	}

	public FeedDescriptor[] getFeeds() {
		return feeds;
	}
}