package org.unitime.timetable.action;

import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.struts2.convention.annotation.Action;
import org.apache.struts2.convention.annotation.Result;
import org.unitime.timetable.defaults.ApplicationProperty;
import org.unitime.timetable.defaults.UserProperty;
import org.unitime.timetable.form.BlankForm;
import org.unitime.timetable.gwt.services.SectioningService;
import org.unitime.timetable.gwt.shared.AcademicSessionProvider.AcademicSessionInfo;
import org.unitime.timetable.gwt.shared.SectioningException;
import org.unitime.timetable.model.Roles;
import org.unitime.timetable.model.Session;
import org.unitime.timetable.model.Student;
import org.unitime.timetable.model.StudentSectioningStatus;
import org.unitime.timetable.model.dao.CourseOfferingDAO;
import org.unitime.timetable.model.dao.SessionDAO;
import org.unitime.timetable.model.dao.StudentDAO;
import org.unitime.timetable.onlinesectioning.OnlineSectioningServer;
import org.unitime.timetable.security.UserAuthority;
import org.unitime.timetable.security.UserQualifier;
import org.unitime.timetable.security.context.UniTimeUserContext;
import org.unitime.timetable.security.qualifiers.SimpleQualifier;
import org.unitime.timetable.security.rights.Right;
import org.unitime.timetable.spring.SpringApplicationContextHolder;

@Action(value = "studentScheduling", results = {
		@Result(name = "main", type = "redirect", location = "/main.action")
	})
public class StudentSchedulingAction extends UniTimeAction<BlankForm> {
	private static final long serialVersionUID = -287721682089077684L;

	private String campus, term, session, prefer;

	public String getCampus() { return campus; }
	public void setCampus(String campus) { this.campus = campus; }
	public String getTerm() { return term; }
	public void setTerm(String term) { this.term = term; }
	public String getSession() { return session; }
	public void setSession(String session) { this.session = session; }
	public String getPrefer() { return prefer; }
	public void setPrefer(String prefer) { this.prefer = prefer; }


	// Session Matching
	protected boolean matchCampus(AcademicSessionInfo info, String campus) {
		if (info.hasExternalCampus() && campus.equalsIgnoreCase(info.getExternalCampus())) return true;
		return campus.equalsIgnoreCase(info.getCampus());
	}

	protected boolean matchTerm(AcademicSessionInfo info, String term) {
		if (info.hasExternalTerm() && term.equalsIgnoreCase(info.getExternalTerm())) return true;
		return term.equalsIgnoreCase(info.getTerm() + info.getYear())
				|| term.equalsIgnoreCase(info.getYear() + info.getTerm())
				|| term.equalsIgnoreCase(info.getTerm() + info.getYear() + info.getCampus());
	}

	protected boolean matchSession(AcademicSessionInfo info, String session) {
		if (info.hasExternalTerm() && info.hasExternalCampus()
				&& session.equalsIgnoreCase(info.getExternalTerm() + info.hasExternalCampus())) return true;
		return session.equalsIgnoreCase(info.getTerm() + info.getYear() + info.getCampus())
				|| session.equalsIgnoreCase(info.getTerm() + info.getYear())
				|| session.equals(info.getSessionId().toString());
	}

	public boolean match(HttpServletRequest request, AcademicSessionInfo info, boolean useDefault) {
		if (campus != null && !matchCampus(info, campus)) return false;
		if (term != null && !matchTerm(info, term)) return false;
		if (session != null && !matchSession(info, session)) return false;
		if (useDefault && campus == null && term == null && session == null)
			return info.getSessionId().equals(sessionContext.getUser().getCurrentAcademicSessionId());
		else
			return true;
	}
	
	@Override
	public String execute() throws Exception {
		String target = buildQueryString();
		boolean useDefault = ApplicationProperty.StudentSchedulingUseDefaultSession.isTrue();
		SectioningService service = (SectioningService)
				SpringApplicationContextHolder.getBean("sectioning.gwt");

		switchInstructorToStudentIfNeeded();
		selectBestAuthority(service, useDefault);

		String redirect = resolveRedirect(service, target, useDefault);
		if (redirect != null) {
			response.sendRedirect(redirect);
			return null;
		}

		return "main";
	}


	// Step 1: Build query string from request parameters
	private String buildQueryString() throws Exception {
		String target = null;
		for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
			if ("prefer".equals(entry.getKey())) continue;
			for (String value : entry.getValue()) {
				if (target == null)
					target = entry.getKey() + "=" + URLEncoder.encode(value, "UTF-8");
				else
					target += "&" + entry.getKey() + "=" + URLEncoder.encode(value, "UTF-8");
			}
		}
		return target;
	}


	// Step 2: If current role is Instructor and also have student role, switch to Student role
	private void switchInstructorToStudentIfNeeded() {
		if (!sessionContext.isAuthenticated()) return;
		if (!Roles.ROLE_INSTRUCTOR.equals(sessionContext.getUser().getCurrentAuthority().getRole())) return;

		// Try same session first
		for (UserAuthority auth : sessionContext.getUser().getAuthorities(
				Roles.ROLE_STUDENT,
				new SimpleQualifier("Session", sessionContext.getUser().getCurrentAcademicSessionId()))) {
			sessionContext.getUser().setCurrentAuthority(auth);
			return;
		}

		// Try different sessions — pick the best default
		if (Roles.ROLE_INSTRUCTOR.equals(sessionContext.getUser().getCurrentAuthority().getRole())) {
			switchToStudentAcrossAllSessions();
		}
	}

	private void switchToStudentAcrossAllSessions() {
		TreeSet<Session> sessions = new TreeSet<>();
		UserAuthority firstStudentAuth = null;
		for (UserAuthority auth : sessionContext.getUser().getAuthorities(Roles.ROLE_STUDENT)) {
			Session s = SessionDAO.getInstance().get((Long) auth.getAcademicSession().getQualifierId());
			if (s != null) sessions.add(s);
			if (firstStudentAuth == null) firstStudentAuth = auth;
		}
		if (sessions.isEmpty()) return;

		Session best = UniTimeUserContext.defaultSession(
				sessions, firstStudentAuth,
				UserProperty.PrimaryCampus.get(sessionContext.getUser()));
		if (best == null) return;

		for (UserAuthority auth : sessionContext.getUser().getAuthorities(
				Roles.ROLE_STUDENT, new SimpleQualifier("Session", best.getUniqueId()))) {
			sessionContext.getUser().setCurrentAuthority(auth);
			return;
		}
	}


	// Step 3: Pick best authority (admin > advisor > student)

	private void selectBestAuthority(SectioningService service, boolean useDefault) {
		if (!sessionContext.isAuthenticated()) return;

		UserAuthority preferred = null;
		preferred = scanForBestAuthority(service.listAcademicSessions(true), useDefault, preferred);
		if (preferred == null)
			preferred = scanForBestAuthority(service.listAcademicSessions(false), useDefault, preferred);
		if (preferred == null && sessionContext.getUser().getCurrentAuthority() != null)
			preferred = scanForBestAuthority(null, useDefault, preferred); // fallback to current session

		if (preferred != null)
			sessionContext.getUser().setCurrentAuthority(preferred);
	}

	private UserAuthority scanForBestAuthority(Iterable<AcademicSessionInfo> sessions,
											   boolean useDefault,
											   UserAuthority current) {
		try {
			Iterable<AcademicSessionInfo> toScan = sessions != null ? sessions
					: sessionContext.getUser().getCurrentAuthority() != null
					? List.of() // handled separately below
					: List.of();

			if (sessions == null) {
				// Fallback: scan current authority's session
				for (UserAuthority auth : sessionContext.getUser().getAuthorities(
						null, sessionContext.getUser().getCurrentAuthority().getAcademicSession())) {
					current = pickBetterAuthority(current, auth);
				}
				return current;
			}

			for (AcademicSessionInfo session : toScan) {
				if (!match(request, session, useDefault)) continue;
				for (UserAuthority auth : sessionContext.getUser().getAuthorities(
						null, new SimpleQualifier("Session", session.getSessionId()))) {
					current = pickBetterAuthority(current, auth);
				}
			}
		} catch (SectioningException ignored) {}
		return current;
	}


	private UserAuthority pickBetterAuthority(UserAuthority current, UserAuthority candidate) {
		if (current == null && Roles.ROLE_STUDENT.equals(candidate.getRole()))
			return candidate;
		if ((current == null || !current.hasRight(Right.StudentSchedulingAdmin))
				&& candidate.hasRight(Right.StudentSchedulingAdvisor))
			return candidate;
		if (candidate.hasRight(Right.StudentSchedulingAdmin))
			return candidate;
		return current;
	}


	// Step 4: Decide where to redirect
	private String resolveRedirect(SectioningService service, String target,
								   boolean useDefault) throws Exception {
		// Admins/Advisors → dashboard
		if (sessionContext.hasPermission(Right.SchedulingDashboard))
			return buildDashboardUrl(target);

		// Students → enrollment or registration
		if (Roles.ROLE_STUDENT.equals(sessionContext.getUser().getCurrentAuthority().getRole())) {
			List<? extends UserQualifier> qualifiers =
					sessionContext.getUser().getCurrentAuthority().getQualifiers("Student");
			if (qualifiers != null && !qualifiers.isEmpty()) {
				String url = routeStudent(service, target, qualifiers.get(0), useDefault);
				if (url != null) return url;
			}
		}


		String url = tryRedirectToSchedulingAssistant(service, target, useDefault);
		if (url != null) return url;


		url = tryRedirectToCourseRequests(service, target, useDefault);
		if (url != null) return url;

		return null;
	}

	private String buildDashboardUrl(String target) {
		if (!sessionContext.hasPermission(Right.StudentSchedulingAdmin)) {
			Number myStudents = CourseOfferingDAO.getInstance().getSession()
					.createQuery("select count(s) from Advisor a inner join a.students s where " +
							"a.externalUniqueId = :user and a.role.reference = :role " +
							"and a.session.uniqueId = :sessionId", Number.class)
					.setParameter("sessionId", sessionContext.getUser().getCurrentAcademicSessionId())
					.setParameter("user", sessionContext.getUser().getExternalUserId())
					.setParameter("role", sessionContext.getUser().getCurrentAuthority().getRole())
					.setCacheable(true).uniqueResult();
			return "onlinesctdash" + qs(target)
					+ (myStudents.intValue() > 0 ? "#mode:%22My%20Students%22@" : "");
		}
		return "onlinesctdash" + qs(target);
	}


	// Step 5: Student-specific routing (enrollment vs registration preference)
	private String routeStudent(SectioningService service, String target,
								UserQualifier qualifier, boolean useDefault) {
		boolean preferCourseRequests = ApplicationProperty.StudentSchedulingPreferCourseRequests.isTrue();
		if (prefer != null)
			preferCourseRequests = "cr".equalsIgnoreCase(prefer) || "crf".equalsIgnoreCase(prefer);

		if (preferCourseRequests) {
			String url = tryRedirectToCourseRequestsForStudent(service, target, qualifier, useDefault);
			if (url != null) return url;
			return tryRedirectToSchedulingAssistantForStudent(service, target, qualifier, useDefault);
		} else {
			String url = tryRedirectToSchedulingAssistantForStudent(service, target, qualifier, useDefault);
			if (url != null) return url;
			return tryRedirectToCourseRequestsForStudent(service, target, qualifier, useDefault);
		}
	}

	private String tryRedirectToSchedulingAssistantForStudent(SectioningService service, String target,
															  UserQualifier qualifier, boolean useDefault) {
		try {
			for (AcademicSessionInfo info : service.listAcademicSessions(true)) {
				if (!match(request, info, useDefault)) continue;
				OnlineSectioningServer server = getSolverServerService()
						.getOnlineStudentSchedulingContainer()
						.getSolver(info.getSessionId().toString());
				if (server == null || !server.getAcademicSession().isSectioningEnabled()) continue;
				Student student = resolveStudent(info.getSessionId(), qualifier);
				if (student == null) continue;
				if (!isEnrollmentEnabled(student)) continue;
				return "sectioning" + qs(target);
			}
		} catch (SectioningException ignored) {}
		return null;
	}

	private String tryRedirectToCourseRequestsForStudent(SectioningService service, String target,
														 UserQualifier qualifier, boolean useDefault) {
		try {
			for (AcademicSessionInfo info : service.listAcademicSessions(false)) {
				if (!match(request, info, useDefault)) continue;
				Student student = resolveStudent(info.getSessionId(), qualifier);
				if (student == null) continue;
				if (!isRegistrationEnabled(student)) continue;
				return "requests" + qs(target);
			}
		} catch (SectioningException ignored) {}
		return null;
	}

	private String tryRedirectToSchedulingAssistant(SectioningService service,
													String target, boolean useDefault) {
		try {
			for (AcademicSessionInfo info : service.listAcademicSessions(true)) {
				if (match(request, info, useDefault))
					return "sectioning" + qs(target);
			}
		} catch (SectioningException ignored) {}
		return null;
	}

	private String tryRedirectToCourseRequests(SectioningService service,
											   String target, boolean useDefault) {
		try {
			for (AcademicSessionInfo info : service.listAcademicSessions(false)) {
				if (match(request, info, useDefault))
					return "requests" + qs(target);
			}
		} catch (SectioningException ignored) {}
		return null;
	}


	// Step 6: Student status helpers
	private Student resolveStudent(long sessionId, UserQualifier qualifier) {
		Student student = Student.findByExternalId(sessionId, qualifier.getQualifierReference());
		if (student == null)
			student = StudentDAO.getInstance().get((Long) qualifier.getQualifierId());
		return student;
	}

	private boolean isEnrollmentEnabled(Student student) {
		StudentSectioningStatus status = student.getEffectiveStatus();
		return status == null || status.hasOption(StudentSectioningStatus.Option.enrollment);
	}

	private boolean isRegistrationEnabled(Student student) {
		StudentSectioningStatus status = student.getEffectiveStatus();
		return status != null && status.hasOption(StudentSectioningStatus.Option.regenabled);
	}

	// Utility
	private String qs(String target) {
		return target == null ? "" : "?" + target;
	}
}