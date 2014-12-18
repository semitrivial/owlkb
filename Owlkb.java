/*
 * Owlkb 2.0, part of RICORDO.
 * On the web: http://open-physiology.org
 * Programmed by Sarala Wimaralatne, reprogrammed by Sam Alexander.
 *
 * Copyright 2014 The Farr Institute
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.InetSocketAddress;
import java.io.*;
import java.util.Set;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TimerTask;
import java.util.Timer;
import java.util.Scanner;

import org.semanticweb.HermiT.Reasoner;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.*;
import org.semanticweb.owlapi.io.OWLOntologyDocumentSource;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.coode.owlapi.manchesterowlsyntax.ManchesterOWLSyntaxEditorParser;
import org.semanticweb.owlapi.util.BidirectionalShortFormProvider;
import org.semanticweb.owlapi.util.BidirectionalShortFormProviderAdapter;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.semanticweb.owlapi.util.AnnotationValueShortFormProvider;
import org.semanticweb.owlapi.util.OWLOntologyImportsClosureSetProvider;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.expression.ShortFormEntityChecker;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

public class Owlkb
{
  /*
   * Variables to be specified by command-line argument.
   */
  public String rname;      // Reasoner name.  Default: "elk"
  public boolean hd_save;   // Whether to save changes to harddrive.  Default: true
  public boolean ucl_syntax;// Whether to parse UCL syntax (by calling LOLS)
  public String kbNs;       // Namespace for RICORDO_### terms.  Default: "http://www.ricordo.eu/ricordo.owl"
  public String kbfilename; // Name of ontology file.  Default: "/home/sarala/testkb/ricordo.owl"
  public boolean help_only; // Whether to show helpscreen and exit.  Default: false
  public int port;          // Port number to listen on.  Default: 20080

  /*
   * Variables to be initialized elsewhere than the command-line
   */
  OWLDataFactory df;
  BidirectionalShortFormProvider shorts;
  BidirectionalShortFormProviderAdapter annovider;
  OWLOntologyImportsClosureSetProvider ontset;
  Set<OWLOntology> imp_closure;

  public static void main(String [] args) throws Exception
  {
    Owlkb owlkb = new Owlkb();
    owlkb.run(args);
  }

 /*
   * Load the ontology and launch a server on user-specified port (default 20080) to serve
   * the owlkb API, as described at http://www.semitrivial.com/owlkb/api.php
   */
  public void run(String [] args) throws Exception
  {
    init_owlkb(this,args);

    if ( help_only == true )
      return;

    /*
     * Load the main ontology
     */
    OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

    logstring( "Loading ontology...");

    OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration();         // If the main ontology imports an RDF fragment,
    config.setMissingOntologyHeaderStrategy(OWLOntologyLoaderConfiguration.MissingOntologyHeaderStrategy.IMPORT_GRAPH);  // prevent that fragment from being saved into the ontology.

    File kbfile;
    OWLOntology ont;
    try
    {
      kbfile = new File(kbfilename);
      ont = manager.loadOntologyFromOntologyDocument(new FileDocumentSource(kbfile),config);
    }
    catch ( Exception e )
    {
      System.out.println( "Could not load file: "+kbfilename );
      System.out.println( "To specify a different filename, run with -file <filename>" );
      System.out.println( "Also, make sure java has permission to access the file." );
      return;
    }

    logstring( "Ontology is loaded.");

    IRI iri = manager.getOntologyDocumentIRI(ont);

    /*
     * Load the ontologies imported by the main ontology (e.g., the reference ontologies)
     */
    imp_closure = ont.getImportsClosure();
    ontset = new OWLOntologyImportsClosureSetProvider(manager, ont);

    /*
     * Establish infrastructure for converting long URLs to short IRIs and vice versa
     * (e.g., converting between "http://purl.org/obo/owlapi/quality#PATO_0000014" and "PATO_0000014")
     */
    shorts = new BidirectionalShortFormProviderAdapter(manager, imp_closure, new SimpleShortFormProvider());
    OWLEntityChecker entityChecker = new ShortFormEntityChecker(shorts);

    /*
     * Infrastructure for searching for classes by label
     */
    List<OWLAnnotationProperty> labeltype_list = new ArrayList<OWLAnnotationProperty>();
    labeltype_list.add(df.getRDFSLabel());
    Map<OWLAnnotationProperty,List<String>> emptymap = new HashMap<OWLAnnotationProperty,List<String>>();
    AnnotationValueShortFormProvider pre_annovider = new AnnotationValueShortFormProvider(labeltype_list, emptymap, ontset );
    annovider = new BidirectionalShortFormProviderAdapter(manager, imp_closure, pre_annovider);

    /*
     * Initiate the reasoner
     */
    logstring( "Establishing "+rname+" reasoner...");

    OWLReasoner r;

    if ( rname.equals("elk") )
    {
      OWLReasonerFactory rf = new ElkReasonerFactory();
      r = rf.createReasoner(ont);
    }
    else
      r = new Reasoner(ont);  //Hermit reasoner

    logstring( "Reasoner established.");

    /*
     * Precompute inferences.
     */
    logstring( "Precomputing inferences...");

    long start_time = System.nanoTime();
    r.precomputeInferences(InferenceType.CLASS_HIERARCHY);

    logstring( "Finished precomputing inferences (took "+(System.nanoTime()-start_time)/1000000+"ms)" );

    /*
     * Launch HTTP server
     */
    logstring( "Initiating server...");

    HttpServer server = HttpServer.create(new InetSocketAddress(port), 0 );
    server.createContext("/subterms", new NetHandler(this, "subterms", r, manager, ont, entityChecker, iri));
    server.createContext("/apinatomy", new NetHandler(this, "apinatomy", r, manager, ont, entityChecker, iri));
    server.createContext("/rtsubterms", new NetHandler(this, "rtsubterms", r, manager, ont, entityChecker, iri));
    server.createContext("/eqterms", new NetHandler(this, "eqterms", r, manager, ont, entityChecker, iri));
    server.createContext("/addlabel", new NetHandler(this, "addlabel", r, manager, ont, entityChecker, iri));
    server.createContext("/terms", new NetHandler(this, "terms", r, manager, ont, entityChecker, iri));
    server.createContext("/instances", new NetHandler(this, "instances", r, manager, ont, entityChecker, iri));
    server.createContext("/labels", new NetHandler(this, "labels", r, manager, ont, entityChecker, iri));
    server.createContext("/search", new NetHandler(this, "search", r, manager, ont, entityChecker, iri));
    server.createContext("/rdfstore", new NetHandler(this, "rdfstore", r, manager, ont, entityChecker, iri));
    server.createContext("/test", new NetHandler(this, "test", r, manager, ont, entityChecker, iri));
    server.createContext("/shortestpath", new NetHandler(this, "shortestpath", r, manager, ont, entityChecker, iri));
    server.createContext("/subgraph", new NetHandler(this, "subgraph", r, manager, ont, entityChecker, iri));

    server.createContext("/gui", new NetHandler(this, "gui", r, manager, ont, entityChecker, iri));

    server.setExecutor(null);
    server.start();

    logstring( "Server initiated.");

    /*
     * Initiate timer for realtime update API
     */
    Timer tmr = new Timer();
    tmr.schedule( new realtime_updater(), 1000, 500 );
  }

  class NetHandler implements HttpHandler
  {
    Owlkb owlkb;
    String srvtype;
    OWLReasoner r;
    OWLOntologyManager m;
    OWLOntology o;
    OWLEntityChecker ec;
    IRI iri;

    public NetHandler(Owlkb owlkb, String srvtype, OWLReasoner r, OWLOntologyManager m, OWLOntology o, OWLEntityChecker ec, IRI iri)
    {
      this.owlkb = owlkb;
      this.srvtype = srvtype;
      this.r = r;
      this.m = m;
      this.o = o;
      this.ec = ec;
      this.iri = iri;
    }

    public void handle(HttpExchange t) throws IOException
    {
      if ( srvtype.equals("gui") )
      {
        send_gui(t);
        return;
      }

      Headers requestHeaders = t.getRequestHeaders();
      int fJson;
      if ( requestHeaders.get("Accept") != null && requestHeaders.get("Accept").contains("application/json") )
        fJson = 1;
      else
        fJson = 0;

      String response;

      String req = t.getRequestURI().toString().substring(2+srvtype.length());
      req = java.net.URLDecoder.decode(req, "UTF-8");

      if ( check_for_non_EL( req, t ) )
        return;

      logstring( "Got request: ["+req+"]" );
      long start_time = System.nanoTime();

      if ( srvtype.equals("labels") || srvtype.equals("search") )
      {
        ArrayList<String> terms = (srvtype.equals("labels") ? getLabels( req, o, owlkb ) : SearchByLabel( req, o, owlkb ));
        if ( terms == null || terms.isEmpty() )
          response = (srvtype.equals("labels") ? "No class by that shortform." : "No class with that label.");
        else
          response = compute_response( terms, fJson, false );
      }
      else
      if ( srvtype.equals("addlabel") )
        response = compute_addlabel_response( owlkb, o, iri, m, req, fJson );
      else
      if ( srvtype.equals("rdfstore") )
        response = compute_rdfstore_response( owlkb, o, iri, m, ec, r, req );
      else
      if ( srvtype.equals("apinatomy") )
        response = compute_apinatomy_response( owlkb, o, iri, m, r, req );
      else
      if ( srvtype.equals("shortestpath") )
        response = compute_shortestpath_response( owlkb, o, iri, m, r, req );
      else
      if ( srvtype.equals("subgraph") )
        response = compute_subgraph_response( owlkb, o, iri, m, r, req );
      else
      try
      {
        OWLClassExpression exp;
        String Manchester_Error = "";

        if ( owlkb.ucl_syntax )
        {
          String LOLS_reply = queryURL( "http://open-physiology.org:5052/uclsyntax/" + URLEncoder.encode(req,"UTF-8") );

          if ( LOLS_reply == null )
          {
            exp = parse_manchester( req, o, ec );
            if ( exp == null )
              Manchester_Error = "Could not connect to LOLS for UCL syntax parsing";
          }
          else
          {
            String error = naive_JSON_parse( LOLS_reply, "Error" );
            if ( error != null && !error.trim().equals("") )
            {
              Manchester_Error = error.trim();
              exp = null;
            }
            else
            {
              String ambigs = naive_JSON_parse( LOLS_reply, "Ambiguities", "\n  [", "\n  ]" );
              if ( ambigs != null && !ambigs.trim().equals("") )
              {
                Manchester_Error = "{\n  \"Ambiguities\":\n  {\n      [" + ambigs.trim() + "]\n  }\n}";
                exp = null;
              }
              else
              {
                String ucl_to_manchester = naive_JSON_parse( LOLS_reply, "Result" );
                if ( ucl_to_manchester == null )
                {
                  Manchester_Error = LOLS_reply.trim();
                  exp = null;
                }
                else
                {
                  exp = parse_manchester( ucl_to_manchester, o, ec );
                  if ( exp == null )
                  {
                    String possible_error = naive_JSON_parse( LOLS_reply, "Possible_error" );
                    if ( possible_error != null )
                      Manchester_Error = possible_error;
                  }
                }
              }
            }
          }
        }
        else
          exp = parse_manchester( req, o, ec );

        if ( exp == null )
        {
          if ( !Manchester_Error.equals("") )
            response = Manchester_Error;
          else
            response = "Malformed Manchester query";
        }
        else
        {
          if ( srvtype.equals("subterms")
          ||   srvtype.equals("eqterms")
          ||   srvtype.equals("instances")
          ||   srvtype.equals("terms") )
          {
            ArrayList<String> terms = null;

            if ( srvtype.equals("subterms") )
              terms = getSubTerms(exp,r,false,false);
            else if ( srvtype.equals("eqterms") )
              terms = addTerm(exp, r, m, o, iri, owlkb);
            else if ( srvtype.equals("instances") )
              terms = getInstances(exp,r);
            else if ( srvtype.equals("terms") )
              terms = getTerms(exp,r);

            response = compute_response( terms, fJson, false );
          }
          else if ( srvtype.equals("rtsubterms") )
          {
            response = compute_rtsubterms_response( exp, r, fJson );
          }
          else if ( srvtype.equals("test") )
          {
            ArrayList<String> terms;

            try
            {
              terms = addTerm(exp, r, m, o, iri, owlkb);
            }
            catch(Exception e)
            {
              terms = null;
            }

            if ( terms == null )
            {
              response = "Malformed Manchester query";
            }
            else
            {
              response = "<h3>Term</h3><ul>";

              for ( String termp : terms )
              {
                response += "<li>" + shorturl(termp) + "</li>";
              }
              response += "</ul>";

              try
              {
                ArrayList<String> subs = getSubTerms(exp,r,false,false);

                if ( subs.size() != 0 )
                {
                  response += "<h3>Subterms</h3><ul>";

                  for ( String termp : subs )
                  {
                    response += "<li>"+shorturl(termp)+"</li>";
                  }
                  response += "</ul>";
                }
              }
              catch(Exception e)
              {
                response = "There was an error getting the results";
              }

              response += "<h5>Runtime</h5>This computation took "+(System.nanoTime() - start_time) / 1000000+"ms to complete";
            }
          }
          else
            response = "Unrecognized request";
        }
      }
      catch(Exception e)
      {
        response = "There was an error getting the results.";
      }

      logstring( "Transmitting response..." );

      send_response( t, response );

      /*
       * Measure computation time in ms.
       */
      long runtime = (System.nanoTime() - start_time) / 1000000;
      logstring( "It took "+runtime+"ms to handle the request." );
    }
  }

  public boolean check_for_non_EL( String req, HttpExchange t )
  {
    /*
     * To do: improve this function, which is currently just a bandaid
     */
    try
    {
      String lower = req.toLowerCase();

      if ( lower.contains(" or ") )
      {
        send_response( t, "Disjunction ('or') is forbidden because it would make the ontology non-EL." );
        return true;
      }
      if ( lower.contains(" not ") || lower.substring(0,4).equals("not ") )
      {
        send_response( t, "Negation ('not') is forbidden because it would make the ontology non-EL." );
        return true;
      }

      return false;
    }
    catch ( Exception e )
    {
      return false;
    }
  }

  public void send_response( HttpExchange t, String response ) throws IOException
  {
      Headers h = t.getResponseHeaders();
      h.add("Cache-Control", "no-cache, no-store, must-revalidate");
      h.add("Pragma", "no-cache");
      h.add("Expires", "0");

      t.sendResponseHeaders(200,response.getBytes().length);
      OutputStream os = t.getResponseBody();
      os.write(response.getBytes());
      os.close();

      logstring( "Response transmitted.");
  }

  /*
   * Some of the following methods (getSubTerms, getEquivalentTerms, getTerms, addTerm)
   * are adapted from methods of the same names written by Sarala W.
   */
  private ArrayList<String> getSubTerms(OWLClassExpression exp, OWLReasoner r, boolean longURI, boolean direct )
  {
    ArrayList<String> idList = new ArrayList<String>();
    NodeSet<OWLClass> subClasses = r.getSubClasses(exp, direct);

    if (subClasses!=null)
    {
      if ( longURI )
      {
        for (Node<OWLClass> owlClassNode : subClasses)
        {
          IRI the_iri = owlClassNode.getEntities().iterator().next().getIRI();
          idList.add(the_iri.toString());
        }
      }
      else
      {
        for (Node<OWLClass> owlClassNode : subClasses)
          idList.add(owlClassNode.getEntities().iterator().next().toStringID());
      }
    }

    return idList;
  }

  private ArrayList<String> getInstances(OWLClassExpression exp, OWLReasoner r)
  {
    ArrayList<String> idList = new ArrayList<String>();
    NodeSet<OWLNamedIndividual> inst = r.getInstances(exp, false);

    if (inst != null)
    {
      for (Node<OWLNamedIndividual> ind : inst)
        idList.add(ind.getEntities().iterator().next().toStringID());
    }

    return idList;
  }

  private ArrayList<String> getEquivalentTerms(OWLClassExpression exp, OWLReasoner r)
  {
    ArrayList<String> idList = new ArrayList<String>();
    Node<OWLClass> equivalentClasses = r.getEquivalentClasses(exp);

    if(equivalentClasses != null)
    {
      try
      {
        idList.add(equivalentClasses.getEntities().iterator().next().toStringID());
      }
      catch (java.util.NoSuchElementException e)
      {
        ;
      }
    }

    return idList;
  }

  public ArrayList<String> getLabels(String shortform, OWLOntology o, Owlkb owlkb)
  {
    OWLEntity e = owlkb.shorts.getEntity(shortform);
    ArrayList<String> idList = new ArrayList<String>();

    if ( e == null || e.isOWLClass() == false )
      return null;

    OWLAnnotationProperty rdfslab = owlkb.df.getRDFSLabel();

    Set<OWLAnnotation> annots = e.getAnnotations(o, rdfslab );

    if ( annots.isEmpty() )
    {
      for ( OWLOntology imp : owlkb.imp_closure )
      {
        annots = e.getAnnotations( imp, rdfslab );
        if ( !annots.isEmpty() )
          break;
      }
    }

    if ( annots.isEmpty() )
      idList.add( "(Unlabeled class)" );   //To do: create "advanced commandline options" one of which chooses Queen's vs. American English
    else
    for ( OWLAnnotation a : annots )
    {
      if ( a.getValue() instanceof OWLLiteral )
        idList.add( ((OWLLiteral)a.getValue()).getLiteral() );
    }

    return idList;
  }

  public ArrayList<String> SearchByLabel(String label, OWLOntology o, Owlkb owlkb)
  {
    Set<OWLEntity> ents = owlkb.annovider.getEntities(label);
    ArrayList<String> idList = new ArrayList<String>();

    if ( ents == null || ents.isEmpty()==true )
      return null;

    for ( OWLEntity e : ents )
    {
      if ( e.isOWLClass() == false || e.asOWLClass().isAnonymous() == true )
        continue;

      idList.add( owlkb.shorts.getShortForm(e) );
    }

    return idList;
  }

  public ArrayList<String> getTerms(OWLClassExpression exp, OWLReasoner r)
  {
    ArrayList<String> idList = new ArrayList<String>();

    idList.addAll(getEquivalentTerms(exp,r));
    idList.addAll(getSubTerms(exp,r,false,false));

    return idList;
  }

  public ArrayList<String> addTerm(OWLClassExpression exp, OWLReasoner r, OWLOntologyManager mgr, OWLOntology ont, IRI iri, Owlkb owlkb)
  {
    ArrayList<String> idList = getEquivalentTerms(exp,r);
    if(idList.isEmpty())
    {
      String ricordoid = String.valueOf(System.currentTimeMillis());
      OWLClass newowlclass = mgr.getOWLDataFactory().getOWLClass(IRI.create(owlkb.kbNs + ricordoid));

      OWLAxiom axiom = mgr.getOWLDataFactory().getOWLEquivalentClassesAxiom(newowlclass, exp);
      Set<OWLAxiom> axiomSet = new HashSet<OWLAxiom>();
      axiomSet.add(axiom);

      mgr.addAxioms(ont,axiomSet);

      if ( owlkb.rname == "elk" )
        r.flush();

      maybe_save_ontology( owlkb, ont, iri, mgr );

      idList.add(newowlclass.toStringID());
      r.precomputeInferences(InferenceType.CLASS_HIERARCHY);
    }
    else
      logstring( "Term already exists, no need to add." );

    return idList;
  }

  /*
   * Convert long URL to short
   * e.g. convert "http://purl.org/obo/owlapi/quality#PATO_0000014" to "PATO_0000014"
   */
  public static String shorturl(String url)
  {
    int i = url.lastIndexOf("#");

    if ( i != -1 )
      return url.substring(i+1);

    i = url.lastIndexOf("/");
    int j = url.indexOf("/");

    if ( i != j )
      return url.substring(i+1);

    return url;
  }

  /*
   * Basic logging to stdout
   */
  public static void logstring( String x )
  {
    System.out.println( x );
  }

  public String compute_response( ArrayList<String> terms, int fJson, boolean longURI )
  {
    String x;

    if ( fJson == 1 )
    {
      x = "[";
      int fencepost = 0;

      for ( String termp : terms )
      {
        if ( fencepost == 0 )
        {
          fencepost = 1;
          x += "'" + (longURI ? termp : shorturl(termp)) +"'";
        }
        else
          x += ", '"+(longURI ? termp : shorturl(termp))+"'";
      }
      x += "]";
    }
    else
    {
      x = "<table><tr><th>ID</th></tr>";

      for ( String termp : terms )
        x += "<tr><td>" + (longURI ? termp : shorturl(termp)) +"</td></tr>";

      x += "</table>";
    }

    return x;
  }

  public String compute_rtsubterms_response( OWLClassExpression exp, OWLReasoner r, int fJson )
  {
    ArrayList<String> terms = getSubTerms( exp, r, false, false );

    return "Not yet implemented";
  }

  public void init_owlkb( Owlkb o, String [] args )
  {
    parse_commandline_arguments(this, args);
    o.df = OWLManager.getOWLDataFactory();
  }

  public void parse_commandline_arguments( Owlkb o, String [] args )
  {
    o.rname = "elk";
    o.hd_save = true;
    o.kbNs = "http://www.ricordo.eu/ricordo.owl#RICORDO_";
    o.kbfilename = "/home/sarala/testkb/ricordo.owl";
    o.help_only = false;
    o.port = 20080;

    int i;
    String flag;

    for ( i = 0; i < args.length; i++ )
    {
      if ( args[i].length() > 2 && args[i].substring(0,2).equals("--") )
        flag = args[i].substring(2).toLowerCase();
      else if ( args[i].length() > 1 && args[i].substring(0,1).equals("-") )
        flag = args[i].substring(1).toLowerCase();
      else
        flag = args[i].toLowerCase();

      if ( flag.equals("help") || flag.equals("h") )
      {
        System.out.println( "Command line options are as follows:"                  );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-file <path to file>"                                  );
        System.out.println( "(Specifies which ontology file to use)"                );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-port <number>"                                        );
        System.out.println( "(Specified which port the server runs on)"             );
        System.out.println( "(Default: 20080)"                                      );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-reasoner elk, or -reasoner hermit"                    );
        System.out.println( "(Specifies which reasoner to use)"                     );
        System.out.println( "(Default: elk)"                                        );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-namespace <iri>"                                      );
        System.out.println( "(Specifies namespace for ontology)"                    );
        System.out.println( "(Default: http://www.ricordo.eu/ricordo.owl#RICORDO_)" );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-save true, or -save false"                            );
        System.out.println( "(Specifies whether owlfile changes are saved)"         );
        System.out.println( "(Default: true)"                                       );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-uclsyntax true, or -uclsyntax false"                  );
        System.out.println( "(Specifies whether OWLKB understands UCL syntax)"      );
        System.out.println( "(Default: false)"                                      );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-help"                                                 );
        System.out.println( "(Displays this helpfile)"                              );
        System.out.println( "" );
        o.help_only = true;
        return;
      }

      if ( flag.equals("port") || flag.equals("p") )
      {
        if ( i+1 < args.length )
        {
          try
          {
            o.port = Integer.parseInt(args[i+1]);
          }
          catch( Exception e )
          {
            System.out.println( "Port must be a number." );
            o.help_only = true;
            return;
          }
          System.out.println( "Owlkb will listen on port "+args[++i] );
        }
        else
        {
          System.out.println( "Which port do you want the server to listen on?" );
          o.help_only = true;
          return;
        }
      }
      else if ( flag.equals("rname") || flag.equals("reasoner") )
      {
        if ( i+1 < args.length && (args[i+1].equals("elk") || args[i+1].equals("hermit")) )
        {
          o.rname = args[++i];
          System.out.println( "Using "+o.rname+" as reasoner" );
        }
        else
        {
          System.out.println( "Valid reasoners are: ELK, HermiT" );
          o.help_only = true;
          return;
        }
      }
      else if ( flag.equals("hd") || flag.equals("hd_save") || flag.equals("save") )
      {
        if ( i+1 < args.length && (args[i+1].equals("t") || args[i+1].equals("true")) )
          o.hd_save = true;
        else if ( i+1 < args.length && (args[i+1].equals("f") || args[i+1].equals("false")) )
        {
          o.hd_save = false;
          System.out.println( "Saving changes to hard drive: disabled." );
        }
        else
        {
          System.out.println( "hd_save can be set to: true, false" );
          o.help_only = true;
          return;
        }
        i++;
      }
      else if ( flag.equals( "uclsyntax" ) || flag.equals("ucl-syntax") || flag.equals("ucl_syntax") )
      {
        if ( i+1 < args.length && (args[i+1].equals("t") || args[i+1].equals("true")) )
        {
          o.ucl_syntax = true;
          System.out.println( "UCL Syntax: enabled" );
        }
        else if ( i+1 < args.length && (args[i+1].equals("f") || args[i+1].equals("false")) )
          o.ucl_syntax = false;
        else
        {
          System.out.println( "uclsyntax can be set to: true, false" );
          o.help_only = true;
          return;
        }
        i++;
      }
      else if (flag.equals("kbns") || flag.equals("ns") || flag.equals("namespace") )
      {
        if ( i+1 < args.length )
        {
          System.out.println( "Using "+args[i+1]+" as ontology namespace." );
          o.kbNs = args[++i];
        }
        else
        {
          System.out.println( "What do you want the ontology's namespace to be?" );
          System.out.println( "Default: http://www.ricordo.eu/ricordo.owl#RICORDO_" );
          o.help_only = true;
          return;
        }
      }
      else if ( flag.equals("kbfilename") || flag.equals("filename") || flag.equals("kbfile") || flag.equals("file") || flag.equals("kb") )
      {
        if ( i+1 < args.length )
        {
          System.out.println( "Using "+args[i+1]+" as ontology filename." );
          o.kbfilename = args[++i];
        }
        else
        {
          System.out.println( "Specify the filename of the ontology." );
          o.help_only = true;
          return;
        }
      }
      else
      {
        System.out.println( "Unrecognized command line argument: "+args[i] );
        System.out.println( "For help, run with HELP as command line argument." );
        o.help_only = true;
        return;
      }
    }
  }

  class realtime_updater extends TimerTask
  {
    public void run()
    {
      /*
       * Maintain list of class expressions that people are monitoring realtime.
       * Maintain list of newly added classes.
       * Periodically check the latter against the former, but limit how much time
       * to do so in one go.
       */
    }
  }

  public void send_gui(HttpExchange t)
  {
    String the_html, the_js;

    try
    {
      try
      {
        the_html = new Scanner(new File("gui.php")).useDelimiter("\\A").next();
        the_js = new Scanner(new File("gui.js")).useDelimiter("\\A").next();
      }
      catch(Exception e)
      {
        send_response( t, "The GUI could not be sent, due to a problem with the html file or the javascript file." );
        return;
      }

      the_html = the_html.replace("@JAVASCRIPT", "<script type='text/javascript'>"+the_js+"</script>");

      send_response( t, the_html );
    }
    catch(Exception e)
    {
      ;
    }
  }

  public String compute_addlabel_response( Owlkb owlkb, OWLOntology o, IRI ontology_iri, OWLOntologyManager m, String req, int fJson )
  {
    int eqpos = req.indexOf('=');

    if ( eqpos == -1 || eqpos == 0 )
    {
      if ( fJson == 1 )
        return "{'syntax error'}";
      else
        return "Invalid syntax.  Syntax: /addlabel/iri=label, e.g.: /addlabel/RICORDO_123=volume of blood";
    }

    String iri = req.substring(0,eqpos);
    String label = req.substring(eqpos+1);

    if ( iri.length() < "RICORDO_".length() || !iri.substring(0,"RICORDO_".length()).equals("RICORDO_") )
    {
      if ( fJson == 1 )
        return "{'non-ricordo class error'}";
      else
        return "Only RICORDO classes can have labels added to them through OWLKB.";
    }

    if ( label.trim().equals("") )
    {
      if ( fJson == 1 )
        return "{'blank label error'}";
      else
        return "Blank labels are not allowed.";
    }

    OWLEntity e = owlkb.shorts.getEntity(iri);
    //ArrayList<String> idList = new ArrayList<String>();

    if ( e == null || e.isOWLClass() == false )
    {
      if ( fJson == 1 )
        return "{'class not found error'}";
      else
        return "The specified class could not be found.  Please make sure you're using the shortform of the iri, e.g., RICORDO_123 instead of http://website.com/RICORDO_123";
    }

    Set<OWLAnnotation> annots = e.getAnnotations(o, owlkb.df.getRDFSLabel() );

    if ( !annots.isEmpty() )
    {
      for ( OWLAnnotation a : annots )
      {
        if ( a.getValue() instanceof OWLLiteral )
        {
          if ( ((OWLLiteral)a.getValue()).getLiteral().equals(label) )
            return (fJson == 1) ? "{'ok'}" : "Class "+iri+" now has label "+escapeHTML(label);
        }
      }
    }

    OWLAnnotation a = owlkb.df.getOWLAnnotation( owlkb.df.getOWLAnnotationProperty(OWLRDFVocabulary.RDFS_LABEL.getIRI()), owlkb.df.getOWLLiteral(label) );
    OWLAxiom axiom = owlkb.df.getOWLAnnotationAssertionAxiom(e.asOWLClass().getIRI(), a);
    m.applyChange(new AddAxiom( o, axiom ));
    logstring( "Added rdfs:label "+label+" to class "+iri+"." );

    maybe_save_ontology( owlkb, o, ontology_iri, m );

    return (fJson == 1) ? "{'ok'}" : "Class "+iri+" now has label "+escapeHTML(label);
  }

  public void maybe_save_ontology( Owlkb owlkb, OWLOntology ont, IRI iri, OWLOntologyManager m )
  {
    if ( owlkb.hd_save == true )
    {
      logstring( "Saving ontology to hard drive..." );

      try
      {
        m.saveOntology(ont,iri);
      }
      catch ( OWLOntologyStorageException e )
      {
        e.printStackTrace(); //To do: proper error handling here
      }

      logstring( "Finished saving ontology to hard drive." );
    }
    else
      logstring( "Skipping writing to hard drive (disabled by commandline argument)." );
  }

  public OWLClassExpression parse_manchester( String manchester, OWLOntology o, OWLEntityChecker ec )
  {
    ManchesterOWLSyntaxEditorParser parser;
    OWLClassExpression exp;

    parser = new ManchesterOWLSyntaxEditorParser(df, manchester);
    parser.setDefaultOntology(o);
    parser.setOWLEntityChecker(ec);

    try
    {
      exp = parser.parseClassExpression();
    }
    catch(Exception e)
    {
      return null;
    }

    return exp;
  }

  public String compute_rdfstore_response( Owlkb owlkb, OWLOntology o, IRI iri, OWLOntologyManager m, OWLEntityChecker ec, OWLReasoner r, String req )
  {
    String x = full_iri_from_full_or_short_iri( req, o );

    if ( x != null )
      return x;

    OWLClassExpression exp = parse_manchester( req, o, ec );

    if ( exp != null )
    {
      ArrayList<String> terms = getSubTerms(exp, r, true, false);

      return compute_response( terms, 1, true );
    }

    return req;
  }

  public String compute_subgraph_response( Owlkb owlkb, OWLOntology o, IRI iri, OWLOntologyManager m, OWLReasoner reasoner, String req )
  {
    String jsonp_header = "", jsonp_footer = "", blank_response = "[]";

    int qmark = req.indexOf("?");
    if ( qmark != -1 )
    {
      String raw_args = req.substring(qmark+1);
      req = req.substring(0,qmark);

      Map<String, String> args = get_args( raw_args );

      String callback = args.get("callback");
      if ( callback != null )
      {
        jsonp_header = "typeof "+callback+" === 'function' && "+callback+"(\n";
        jsonp_footer = ");";
        blank_response = jsonp_header + "[]" + jsonp_footer;
      }
    }

    req = "," + req.replace("fma:", "http://purl.org/obo/owlapi/fma%23FMA_");

    String feather_response = queryFeather("subgraph", req);

    if ( feather_response == null )
      return jsonp_header + "?" + jsonp_footer;
    else
      return jsonp_header + feather_response + jsonp_footer;
  }

  public String compute_shortestpath_response( Owlkb owlkb, OWLOntology o, IRI iri, OWLOntologyManager m, OWLReasoner reasoner, String req )
  {
    String jsonp_header = "", jsonp_footer = "", blank_response = "[]";

    int qmark = req.indexOf("?");
    if ( qmark != -1 )
    {
      String raw_args = req.substring(qmark+1);
      req = req.substring(0,qmark);

      Map<String, String> args = get_args( raw_args );

      String callback = args.get("callback");
      if ( callback != null )
      {
        jsonp_header = "typeof "+callback+" === 'function' && "+callback+"(\n";
        jsonp_footer = ");";
        blank_response = jsonp_header + "[]" + jsonp_footer;
      }
    }

    req = req.replace("fma:", "http://purl.org/obo/owlapi/fma%23FMA_");
    String feather_response = queryFeather("shortpath", req);

    if ( feather_response == null )
      return jsonp_header + "?" + jsonp_footer;
    else
      return jsonp_header + feather_response + jsonp_footer;
  }

  public String compute_apinatomy_response( Owlkb owlkb, OWLOntology o, IRI iri, OWLOntologyManager m, OWLReasoner reasoner, String req )
  {
    OWLAnnotationProperty rdfslab = owlkb.df.getRDFSLabel();
    String response = "[\n";
    boolean isFirstResult = true;
    String jsonp_header = "", jsonp_footer = "", blank_response = "[]";

    int qmark = req.indexOf("?");
    if ( qmark != -1 )
    {
      String raw_args = req.substring(qmark+1);
      req = req.substring(0,qmark);

      Map<String, String> args = get_args( raw_args );

      String callback = args.get("callback");
      if ( callback != null )
      {
        jsonp_header = "typeof "+callback+" === 'function' && "+callback+"(\n";
        jsonp_footer = ");";
        blank_response = jsonp_header + "[]" + jsonp_footer;
      }
    }

    req = req.replace("fma:", "FMA_");

    List<String> shortforms = Arrays.asList(req.split(","));

    /*
     * Max size chosen based on FMA's most prolific class, FMA_21792 ("Fascia of muscle"), which has 222 subs
     */
    if ( shortforms.size() > 250 )
      return blank_response;

    if ( req.substring(0,6).equals( "24tile" ) )
    {
      try
      {
        return jsonp_header + (new Scanner(new File("24tiles.dat")).useDelimiter("\\A").next()) + jsonp_footer;
      }
      catch(Exception e)
      {
        return blank_response;
      }
    }

    if ( req.equals("pkpd_base") )
    {
      try
      {
        return jsonp_header + (new Scanner(new File("pkpdroot.dat")).useDelimiter("\\A").next()) + jsonp_footer;
      }
      catch(Exception e)
      {
        return blank_response;
      }
    }


    for ( String shortform : shortforms )
    {
      OWLEntity e = owlkb.shorts.getEntity(shortform);

      if ( e == null || (e.isOWLClass() == false && e.isOWLNamedIndividual() == false) )
        continue;

      if ( isFirstResult )
        isFirstResult = false;
      else
        response += ",\n";

      response += "  {\n    \"_id\": \"" + escapeHTML(shortform) + "\",\n";

      String the_label = get_one_rdfs_label( e, o, owlkb, rdfslab );

      if ( the_label == null )
        the_label = shortform;

      String feather_response = queryFeatherweight(shortform);

      if ( feather_response == null )
        the_label = the_label + " (?)";
      else
        the_label = the_label + " (" + feather_response + ")";

      response += "    \"name\": \"" + escapeHTML(the_label) + "\",\n    \"sub\":\n    [\n";

      if ( e.isOWLClass() )
      {
        List<Apinatomy_Sub> subs = get_apinatomy_subs(e, owlkb, reasoner, o);
        boolean isFirstSub = true;

        for ( Apinatomy_Sub sub : subs )
        {
          if ( isFirstSub )
            isFirstSub = false;
          else
            response += ",\n";

          response += "      {\n        \"type\": \"" + escapeHTML(sub.type) + "\",\n";
          response += "        \"entity\":\n        {\n          \"_id\": \"" + escapeHTML(sub.id) + "\"\n        }\n      }";
        }
      }

      response += "\n    ]\n  }";
    }

    response = response.replace( "FMA_", "fma:" );

    response += "\n]";
    return jsonp_header + response + jsonp_footer;
  }

  public String queryURL(String urlstring)
  {
    StringBuilder buf = null;
    Reader r = null;

    try
    {
      URL url = new URL(urlstring);
      URLConnection con = url.openConnection();
      r = new InputStreamReader(con.getInputStream(), "UTF-8");
      buf = new StringBuilder();

      while (true)
      {
        int ch = r.read();
        if (ch < 0)
          break;
        buf.append((char) ch);
      }
      return buf.toString();
    }
    catch(Exception e)
    {
      return null;
    }
  }

  public String queryFeatherweight(String x)
  {
    String s = queryURL("http://open-physiology.org:5053/count-recursive/http://purl.org/obo/owlapi/fma%23"+x);
    s = s.substring("{\"Results\": [".length() );
    s = s.substring(0,s.length()-2);

    return s;
  }

  public String queryFeather( String command, String x )
  {
    return queryURL("http://open-physiology.org:5053/"+command+"/"+x);
  }

  public List<Apinatomy_Sub> get_apinatomy_subs( OWLEntity e, Owlkb owlkb, OWLReasoner r, OWLOntology o )
  {
    List<Apinatomy_Sub> response = new ArrayList<Apinatomy_Sub>();

    if ( !( e instanceof OWLClass ) )
      return response;

    OWLClass c = e.asOWLClass();

    ArrayList<String> subclasslist = getSubTerms(c, r, false, true);

    for ( String subclass : subclasslist )
    {
      String the_id = shorturl( subclass );

      if ( !the_id.equals( "Nothing" ) )
        response.add( new Apinatomy_Sub( the_id, "subclass" ) );
    }

    for ( OWLOntology imp : owlkb.imp_closure )
    {
      Set<OWLClassExpression> supers = c.getSuperClasses(imp);

      for ( OWLClassExpression exp : supers )
      {
        if ( !( exp instanceof OWLObjectSomeValuesFrom ) )
          continue;

        String type = null;
        OWLRestriction restrict = (OWLRestriction) exp;
        Set<OWLObjectProperty> objprops = restrict.getObjectPropertiesInSignature();

        for ( OWLObjectProperty objprop : objprops )
        {
          String objprop_short = shorturl( objprop.toStringID() );

          if ( objprop_short.equals( "regional_part" ) )
            type = "regional part";
          else
          if ( objprop_short.equals( "constitutional_part" ) )
            type = "constitutional part";

          break;
        }

        if ( type == null )
          continue;

        Set<OWLClass> classes_in_signature = restrict.getClassesInSignature();
        for ( OWLClass class_in_signature : classes_in_signature )
        {
          response.add( new Apinatomy_Sub( shorturl(class_in_signature.toStringID()), type ) );
          break;
        }
      }

      Set<OWLIndividual> inds = c.getIndividuals(imp);

      for ( OWLIndividual ind : inds )
      {
        if ( !(ind instanceof OWLNamedIndividual) )
          continue;

        response.add( new Apinatomy_Sub( shorturl( ind.asOWLNamedIndividual().getIRI().toString() ), "subclass" ) );
      }
    }

    return response;
  }

  class Apinatomy_Sub
  {
    public String id;
    public String type;

    public Apinatomy_Sub(String id, String type)
    {
      this.id = id;
      this.type = type;
    }
  }

  public String get_one_rdfs_label( OWLEntity e, OWLOntology o, Owlkb owlkb, OWLAnnotationProperty rdfslab )
  {
    Set<OWLAnnotation> annots = e.getAnnotations(o, rdfslab);

    if ( annots.isEmpty() )
    {
      for ( OWLOntology imp : owlkb.imp_closure )
      {
        annots = e.getAnnotations( imp, rdfslab );
        if ( !annots.isEmpty() )
          break;
      }
    }

    if ( annots.isEmpty() )
      return null;

    for ( OWLAnnotation a : annots )
    {
      if ( a.getValue() instanceof OWLLiteral )
        return ((OWLLiteral)a.getValue()).getLiteral();
    }

    return null;
  }

  /*
   * escapeHTML taken from Bruno Eberhard at stackoverflow
   */
  public static String escapeHTML(String s)
  {
    StringBuilder out = new StringBuilder(Math.max(16, s.length()));

    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);

      if (c > 127 || c == '"' || c == '<' || c == '>' || c == '&')
      {
        out.append("&#");
        out.append((int) c);
        out.append(';');
      }
      else
        out.append(c);
    }
    return out.toString();
  }

  String full_iri_from_full_or_short_iri( String x, OWLOntology o )
  {
    OWLEntity e = shorts.getEntity(x);

    if ( e != null )
      return e.getIRI().toString();

    if ( o.containsClassInSignature(IRI.create(x)) )
      return x;

    return null;
  }

  public static Map<String, String> get_args(String query)
  {
    Map<String, String> result = new HashMap<String, String>();
    try
    {
      for (String param : query.split("&"))
      {
        String pair[] = param.split("=");
        if (pair.length > 1)
          result.put(URLDecoder.decode(pair[0],"UTF-8"), URLDecoder.decode(pair[1],"UTF-8"));
        else
          result.put(URLDecoder.decode(pair[0],"UTF-8"), "");
      }
    }
    catch( Exception e )
    {
      ;
    }
    return result;
  }

  public String naive_JSON_parse(String json, String key)
  {
    return naive_JSON_parse( json, key, "\"", "\"" );
  }

  public String naive_JSON_parse(String json, String key, String start, String end )
  {
    String needle = "\"" + key + "\":";
    int pos = json.indexOf(needle);

    if ( pos == -1 )
      return null;

    pos += needle.length();

    pos = json.indexOf(start, pos);

    if ( pos == -1 )
      return null;

    pos += start.length();

    int endpos = json.indexOf(end, pos);

    if ( endpos == -1 )
      return null;

    return json.substring(pos,endpos);
  }
}
