package org.jasig.portal;


import javax.servlet.*;
import javax.servlet.jsp.*;
import javax.servlet.http.*;

import java.io.*;
import java.util.*;
import java.text.*;
import java.sql.*;
import java.net.*;
import org.w3c.dom.*;
import org.apache.xalan.xpath.*;
import org.apache.xalan.xslt.*;
import org.apache.xml.serialize.*;


/**
 * LayoutBean is the central piece of the portal. It is responsible for presenting 
 * content to the client given a request. It also handles basic user interactions,
 * passing appropriate parameters to the stylesheets, channels or userLayoutManager
 * @author Peter Kharchenko
 * @version $Revision$
 */
public class LayoutBean extends GenericPortalBean
{       

    // all channel content/parameters/caches/etc are managed here
    ChannelManager channelManager;
    
    // stylesheet sets for the first two major XSL transformations
    // userLayout -> structuredLayout -> target markup language
    private StylesheetSet structuredLayoutSS;
    private StylesheetSet userLayoutSS;
    
    // contains information relating client names to media and mime types
    private MediaManager mediaM;
    
    XSLTProcessor sLayoutProcessor;
    XSLTProcessor uLayoutProcessor;


    /**
     * Constructor initializes media manager and stylesheet sets.
     */
    public LayoutBean ()
    {
	// init the media manager
	String fs=System.getProperty("file.separator");
	String propertiesDir=getPortalBaseDir()+"properties"+fs;
	mediaM=new MediaManager(propertiesDir+"media.properties",propertiesDir+"mime.properties",propertiesDir+"serializer.properties");
	
	
	// create a stylesheet set for userLayout transformations
	userLayoutSS=new StylesheetSet();
	userLayoutSS.setMediaProps(propertiesDir+"media.properties ");

	// manual initialization of the StylesheetSet.
	// This is just for an example. I would recommend initializing the stylesheet set from an xml file instead
	userLayoutSS.addStyleSheet(new StylesheetDescription(getPortalBaseDir()+"webpages"+fs+"stylesheets"+fs+"LayoutBean"+fs+"uLayout2sLayout.xsl","text/xsl","","netscape","",false));

	
	
	// create a stylesheet set for structuredLayout transformations
	structuredLayoutSS=new StylesheetSet(getPortalBaseDir()+"webpages"+fs+"stylesheets"+fs+"LayoutBean"+fs+"StructuredLayout.ssl");
	structuredLayoutSS.setMediaProps(propertiesDir+"media.properties ");

	// instantiate the processors
	try {
	    sLayoutProcessor = XSLTProcessorFactory.getProcessor();
	    uLayoutProcessor = XSLTProcessorFactory.getProcessor();
	} catch (Exception e) {
	    Logger.log (Logger.ERROR, "LayoutBean::LayoutBean() : caught an exception while trying initialize XLST processors. "+e);
	}
    }
    
    /**
     * Gets the username from the session
     * @param the servlet request object
     * @return the username
     */
    public String getUserName (HttpServletRequest req)
    {
	HttpSession session = req.getSession (false);
	return (String) session.getAttribute ("userName");
    }
    
    /**
     * Renders the current state of the portal into the target markup language
     * (basically, this is the main method that does all the work)
     * @param the servlet request object
     * @param the servlet response object
     * @param the JspWriter object
     */
    public void writeContent (HttpServletRequest req, HttpServletResponse res, JspWriter out)
    {    
	// This function does ALL the content gathering/presentation work.
	// The following filter sequence is processed:
	//        userLayoutXML (in UserLayoutManager)
	//              |
	//        uLayout2sLayout filter
	//              |
	//        HeaderAndFooterIncorporation filter
	//              |
	//        ChannelIncorporation filter
	//              |
	//        Serializer (XHTML/WML/HTML/etc.)
	//              | 
	//        JspWriter
	//

	try  {           

	    // A userLayout node that transformations will be applied to. 
	    // see "userLayoutRoot" parameter
	    Node rElement;

	    // get the layout
	    UserLayoutManager uLayoutManager=new UserLayoutManager(req,getUserName(req));	    

	    // process events that have to be handed directly to the userLayoutManager.
	    // (examples of such events are "remove channel", "minimize channel", etc.
	    //  basically things that directly affect the userLayout structure)
	    processUserLayoutParameters(req,uLayoutManager);


	    // set the response mime type 
	    res.setContentType(mediaM.getReturnMimeType(req));
	    //	    Logger.log(Logger.DEBUG,"(media,mime)=(\""+mediaM.getMedia(req)+"\",\""+mediaM.getReturnMimeType(req)+"\")");
	    
	    // set up the transformation pipeline

	    // get a serializer appropriate for the target media
	    BaseMarkupSerializer markupSerializer=mediaM.getSerializer(req,out);

	    // set up the serializer
	    markupSerializer.asContentHandler();


	    // set up the channelManager
	    if(channelManager==null) channelManager=new ChannelManager(req,res);
	    else channelManager.setReqNRes(req,res);

	    // initialize ChannelIncorporationFilter
	    ChannelIncorporationFilter cf = new ChannelIncorporationFilter(markupSerializer,channelManager);

	    sLayoutProcessor.processStylesheet(structuredLayoutSS.getStylesheet(req));
	    sLayoutProcessor.setDocumentHandler(cf);

	    // deal with parameters that are meant for the LayoutBean
	    HttpSession session = req.getSession (false);

	    // "layoutRoot" signifies a node of the userLayout structure
	    // that will serve as a root for constructing structuredLayout
	    String req_layoutRoot=req.getParameter("userLayoutRoot");
	    String ses_layoutRoot=(String) session.getAttribute("userLayoutRoot");
	    if(req_layoutRoot!=null) {
		session.setAttribute("userLayoutRoot",req_layoutRoot);
		rElement=uLayoutManager.getNode(req_layoutRoot);
		if(rElement==null) { rElement=uLayoutManager.getRoot();
		Logger.log(Logger.DEBUG,"LayoutBean::writeChanels() : attempted to set layoutRoot to nonexistent node \""+req_layoutRoot+"\", setting to the main root node instead.");
		} else {
		    //		    Logger.log(Logger.DEBUG,"LayoutBean::writeChanels() : set layoutRoot to "+req_layoutRoot);
		}
	    } else if(ses_layoutRoot!=null) { rElement=uLayoutManager.getNode(ses_layoutRoot);
	    //	    Logger.log(Logger.DEBUG,"LayoutBean::writeChannels() : retrieved the session value for layoutRoot=\""+ses_layoutRoot+"\"");
	    }
	    else  rElement=uLayoutManager.getRoot();
	    
	    // "stylesheetTarget" allows to specify one of two stylesheet sets "u" or "s" to
	    // a selected member of which the stylesheet parameters will be passed
	    // "u" stands for the stylesheet set used for userLayout->structuredLayout transform.,
	    // and "s" is a set used for structuedLayout->pageContent transformation.
	    
	    String stylesheetTarget=null;
	    Hashtable upTable=new Hashtable();
	    Hashtable spTable=new Hashtable();
	    if((stylesheetTarget=(req.getParameter("stylesheetTarget")))!=null) {
		if(stylesheetTarget.equals("u")) {
		    Enumeration e=req.getParameterNames();
		    if(e!=null) {
			while(e.hasMoreElements()) {
			    String pName=(String) e.nextElement();
			    if(!pName.equals("stylesheetTarget"))
				upTable.put(pName,req.getParameter(pName));
			}
		    }
		    
		} else if(stylesheetTarget.equals("s")) {
		    Enumeration e=req.getParameterNames();
		    if(e!=null) {
			while(e.hasMoreElements()) {
			    String pName=(String) e.nextElement();
			    if(!pName.equals("stylesheetTarget"))
			       spTable.put(pName,req.getParameter(pName));
			}
		    }
		}
	    }
		
	    // process old stylesheet params and add new ones.
	    // Because session can store only strings, I have two strings
	    // (one for userLayoutStylesheet, one for structuredLayoutStylesheet)
	    // listing the names of the parameters. The values of the parameters
	    // are stored in the sesion.
	    
	    // merge the old parameter values with the new ones
	    String upNames=(String) session.getAttribute("userLayoutParameterNames");
	    if(upNames!=null){
		StringTokenizer st = new StringTokenizer(upNames,"&");
		while (st.hasMoreTokens()) {
		    String pName=st.nextToken();
		    if(!upTable.containsKey(pName))
			upTable.put(pName,session.getAttribute(pName));
		}
	    }
	    // set stylesheet params, save parameters in a session, generate a new userLayoutParameterNames string
	    upNames="";
	    for (Enumeration e = upTable.keys() ; e.hasMoreElements() ;) {
		String pName=(String) e.nextElement();
		upNames+=pName+"&";
		String pValue=(String) upTable.get(pName);
		session.setAttribute(pName,pValue);
		uLayoutProcessor.setStylesheetParam(pName,uLayoutProcessor.createXString(pValue));
	    } 
	    session.setAttribute("userLayoutParameterNames",upNames);
	    

	    // merge the old parameter values with the new ones
	    String spNames=(String) session.getAttribute("structuredLayoutParameterNames");
	    if(spNames!=null){
		StringTokenizer st = new StringTokenizer(spNames,"&");
		while (st.hasMoreTokens()) {
		    String pName=st.nextToken();
		    if(!spTable.containsKey(pName))
			spTable.put(pName,session.getAttribute(pName));
		}
	    }
	    // set stylesheet params, save parameters in a session, generate a new userLayoutParameterNames string
	    spNames="";
	    for (Enumeration e = spTable.keys() ; e.hasMoreElements() ;) {
		String pName=(String) e.nextElement();
		spNames+=pName+"&";
		String pValue=(String) spTable.get(pName);
		session.setAttribute(pName,pValue);
		sLayoutProcessor.setStylesheetParam(pName,sLayoutProcessor.createXString(pValue));
	    } 
	    session.setAttribute("structuredLayoutParameterNames",spNames);
	    

	    
	    // all the parameters are set up, fire up the filter transforms
	    uLayoutProcessor.process(new XSLTInputSource(rElement),userLayoutSS.getStylesheet(),new XSLTResultTarget(sLayoutProcessor));
	    
	    
	}
	catch (Exception e) {
	    Logger.log (Logger.ERROR, e);
	}
    }

    
    /**
     * Processes "userLayoutTarget" and a corresponding(?) "action".
     * Function basically calls UserLayoutManager functions that correspond
     * to the requested action.
     * @param the servlet request object
     * @param the userLayout manager object
     */
    private void processUserLayoutParameters(HttpServletRequest req,UserLayoutManager man)
    {
	String layoutTarget;
	//	HttpSession session = req.getSession (false);
	if((layoutTarget=req.getParameter("userLayoutTarget"))!=null) {
	    String action=req.getParameter("action");
	    // determine what action is
	    if(action.equals("minimize")) {
		man.minimizeChannel(layoutTarget);
		channelManager.passLayoutEvent(layoutTarget,new LayoutEvent(LayoutEvent.MINIMIZE_BUTTON_EVENT));
	    } else if(action.equals("remove")) {
		man.removeChannel(layoutTarget);
	    } else if(action.equals("edit")) {
		channelManager.passLayoutEvent(layoutTarget,new LayoutEvent(LayoutEvent.EDIT_BUTTON_EVENT));
	    } else if(action.equals("help")) {
		channelManager.passLayoutEvent(layoutTarget,new LayoutEvent(LayoutEvent.HELP_BUTTON_EVENT));
	    } else if(action.equals("detach")) {
		channelManager.passLayoutEvent(layoutTarget,new LayoutEvent(LayoutEvent.DETACH_BUTTON_EVENT));
	    }
	}
    }
}


