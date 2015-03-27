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

import java.io.File;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.Node;
import org.semanticweb.owlapi.reasoner.NodeSet;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.coode.owlapi.manchesterowlsyntax.ManchesterOWLSyntaxEditorParser;
import org.semanticweb.owlapi.util.BidirectionalShortFormProvider;
import org.semanticweb.owlapi.util.BidirectionalShortFormProviderAdapter;
import org.semanticweb.owlapi.util.AnnotationValueShortFormProvider;
import org.semanticweb.owlapi.util.OWLOntologyImportsClosureSetProvider;
import org.semanticweb.owlapi.expression.OWLEntityChecker;
import org.semanticweb.owlapi.expression.ShortFormEntityChecker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.Headers;

public class Owlkb
{
  /*
   * Variables to be specified by command-line argument.
   */
  public String rname;      // Reasoner name.  Default: "elk"
  public boolean hd_save;   // Whether to save changes to harddrive.  Default: true
  public String ucl_syntax; // Endpoint for UCL syntax server.  Default: null
  public String kbNs;       // Namespace for RICORDO_### terms.  Default: "http://www.ricordo.eu/ricordo.owl"
  public String kbfilename; // Name of ontology file.  Default: "/home/sarala/testkb/ricordo.owl"
  public boolean help_only; // Whether to show helpscreen and exit.  Default: false
  public int port;          // Port number to listen on.  Default: 20080
  public String sparql;     // URL of SPARQL endpoint
  public boolean get_counts_from_feather; // For easy reversion in case SPARQL doesn't work

  /*
   * Variables to be initialized elsewhere than the command-line
   */
  OWLDataFactory df;
  BidirectionalShortFormProvider shorts;
  BidirectionalShortFormProviderAdapter annovider;
  OWLOntologyImportsClosureSetProvider ontset;
  Set<OWLOntology> imp_closure;
  OWLAnnotationProperty rdfslab;

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
    init_owlkb(args);

    if ( help_only )
      return;

    /*
     * Load the main ontology
     */
    OWLOntologyManager manager = OWLManager.createOWLOntologyManager();

    logstring( "Loading ontology...");

    OWLOntologyLoaderConfiguration config = new OWLOntologyLoaderConfiguration();         // If the main ontology imports an RDF fragment,
    config = config.setMissingOntologyHeaderStrategy(OWLOntologyLoaderConfiguration.MissingOntologyHeaderStrategy.IMPORT_GRAPH);  // prevent that fragment from being saved into the ontology.

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
    shorts = new BidirectionalShortFormProviderAdapter(manager, imp_closure, new org.semanticweb.owlapi.util.SimpleShortFormProvider());
    OWLEntityChecker entityChecker = new ShortFormEntityChecker(shorts);

    /*
     * Infrastructure for searching for classes by label
     */
    List<OWLAnnotationProperty> labeltype_list = new ArrayList<OWLAnnotationProperty>();
    labeltype_list.add(rdfslab);
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
      r = new org.semanticweb.HermiT.Reasoner(ont);  //Hermit reasoner

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

    HttpServer server = HttpServer.create(new java.net.InetSocketAddress(port), 0 );
    server.createContext("/subterms", new NetHandler("subterms", r, manager, ont, entityChecker, iri));
    server.createContext("/siblings", new NetHandler("siblings", r, manager, ont, entityChecker, iri));
    server.createContext("/subhierarchy", new NetHandler("subhierarchy", r, manager, ont, entityChecker, iri));
    server.createContext("/apinatomy", new NetHandler("apinatomy", r, manager, ont, entityChecker, iri));
    server.createContext("/eqterms", new NetHandler("eqterms", r, manager, ont, entityChecker, iri));
    server.createContext("/addlabel", new NetHandler("addlabel", r, manager, ont, entityChecker, iri));
    server.createContext("/terms", new NetHandler("terms", r, manager, ont, entityChecker, iri));
    server.createContext("/instances", new NetHandler("instances", r, manager, ont, entityChecker, iri));
    server.createContext("/labels", new NetHandler("labels", r, manager, ont, entityChecker, iri));
    server.createContext("/search", new NetHandler("search", r, manager, ont, entityChecker, iri));
    server.createContext("/rdfstore", new NetHandler("rdfstore", r, manager, ont, entityChecker, iri));
    server.createContext("/test", new NetHandler("test", r, manager, ont, entityChecker, iri));
    server.createContext("/shortestpath", new NetHandler("shortestpath", r, manager, ont, entityChecker, iri));
    server.createContext("/generate-triples", new NetHandler("generate-triples", r, manager, ont, entityChecker, iri));
    server.createContext("/subgraph", new NetHandler("subgraph", r, manager, ont, entityChecker, iri));

    server.createContext("/gui", new NetHandler("gui", r, manager, ont, entityChecker, iri));

    server.setExecutor(null);
    server.start();

    logstring( "Server initiated.");
  }

  class NetHandler implements com.sun.net.httpserver.HttpHandler
  {
    String srvtype;
    OWLReasoner r;
    OWLOntologyManager m;
    OWLOntology o;
    OWLEntityChecker ec;
    IRI iri;

    public NetHandler(String srvtype, OWLReasoner r, OWLOntologyManager m, OWLOntology o, OWLEntityChecker ec, IRI iri)
    {
      this.srvtype = srvtype;
      this.r = r;
      this.m = m;
      this.o = o;
      this.ec = ec;
      this.iri = iri;
    }

    public void handle(HttpExchange t) throws java.io.IOException
    {
      if ( srvtype.equals("gui") )
      {
        send_gui(t);
        return;
      }

      Headers requestHeaders = t.getRequestHeaders();
      boolean fJson = ( requestHeaders.get("Accept") != null && requestHeaders.get("Accept").contains("application/json") );
      boolean verbose = false;

      String response;

      String req = t.getRequestURI().toString().substring(2+srvtype.length());

      Map<String,String> args;
      int qmark = req.indexOf("?");
      if ( qmark != -1 )
      {
        String raw_args = req.substring(qmark+1);
        req = req.substring(0,qmark);
        args = get_args( raw_args );

        if ( !fJson && args.containsKey( "json" ) )
          fJson = true;

        if ( args.containsKey( "verbose" ) )
        {
          verbose = true;
          fJson = true;
        }
      }
      else
        args = new HashMap<String,String>();

      req = URLDecode(req);

      if ( check_for_non_EL( req, t ) )
        return;

      logstring( "Got request: ["+req+"]" );
      long start_time = System.nanoTime();

      if ( srvtype.equals("labels") || srvtype.equals("search") )
      {
        boolean isLabels = srvtype.equals("labels");

        ArrayList<String> terms = (isLabels ? getLabels( req, o ) : SearchByLabel( req, o, verbose ));

        if ( terms == null || terms.isEmpty() )
          response = (isLabels ? "No class by that shortform." : "No class with that label.");
        else
          response = compute_response( terms, fJson, false, verbose && !isLabels );
      }
      else
      if ( srvtype.equals("addlabel") )
        response = compute_addlabel_response( o, iri, m, req, fJson );
      else
      if ( srvtype.equals("rdfstore") )
        response = compute_rdfstore_response( o, iri, m, ec, r, req );
      else
      if ( srvtype.equals("apinatomy") )
      {
        response = compute_apinatomy_response( o, iri, m, r, req );
        fJson = true;
      }
      else
      if ( srvtype.equals("generate-triples") )
      {
        if ( t.getRemoteAddress().getAddress().isLoopbackAddress() )
          response = compute_generate_triples_response( o, iri, m, r, req );
        else
          response = "{\"error\": \"Only requests originating from localhost can run generate-triples\"}";

        fJson = true;
      }
      else
      if ( srvtype.equals("shortestpath") )
      {
        response = compute_shortestpath_response( o, iri, m, r, req );
        fJson = true;
      }
      else
      if ( srvtype.equals("subgraph") )
      {
        response = compute_subgraph_response( o, iri, m, r, req );
        fJson = true;
      }
      else
      try
      {
        OWLClassExpression exp;
        String Manchester_Error = "";

        if ( ucl_syntax != null )
        {
          String LOLS_reply = queryURL( ucl_syntax + URLEncode(req) );

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
                Manchester_Error = "{\n  \"Ambiguities\":\n  [\n    " + ambigs.trim() + "\n  ]\n}";
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
          ||   srvtype.equals("siblings")
          ||   srvtype.equals("eqterms")
          ||   srvtype.equals("instances")
          ||   srvtype.equals("terms") )
          {
            ArrayList<String> terms = null;

            if ( srvtype.equals("subterms") )
              terms = getSubTerms(exp,r,false,false,verbose);
            else if ( srvtype.equals("siblings") )
              terms = getSiblings(exp,r,false,false,verbose);
            else if ( srvtype.equals("eqterms") )
              terms = addTerm(exp,r,m,o,iri,verbose );
            else if ( srvtype.equals("instances") )
              terms = getInstances(exp,r,verbose);
            else if ( srvtype.equals("terms") )
              terms = getTerms(exp,r,verbose);

            response = compute_response( terms, fJson, false, verbose );
          }
          else if ( srvtype.equals("subhierarchy") )
          {
            response = compute_subhierarchy_response( exp, r );
          }
          else if ( srvtype.equals("test") )
            response = compute_demo_response( exp, r, m, o, iri, start_time, fJson, verbose );
          else
            response = "Unrecognized request";
        }
      }
      catch(Exception e)
      {
        response = "There was an error getting the results.";
      }

      String callback = args.get("callback"); // JSONP support
      if ( callback != null )
      {
        String jsonp_header = "typeof "+callback+" === 'function' && "+callback+"(\n";
        response = jsonp_header + response + ");";
      }

      logstring( "Transmitting response..." );

      send_response( t, response, fJson );

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
        send_response( t, "Disjunction ('or') is forbidden because it would make the ontology non-EL.", false );
        return true;
      }
      if ( lower.contains(" not ") || lower.substring(0,4).equals("not ") )
      {
        send_response( t, "Negation ('not') is forbidden because it would make the ontology non-EL.", false );
        return true;
      }

      return false;
    }
    catch ( Exception e )
    {
      return false;
    }
  }

  public void send_response( HttpExchange t, String response, boolean fJson ) throws java.io.IOException
  {
    Headers h = t.getResponseHeaders();
    h.add("Cache-Control", "no-cache, no-store, must-revalidate");
    h.add("Pragma", "no-cache");
    h.add("Expires", "0");

    if ( fJson )
      h.add("Content-Type", "application/json");

    t.sendResponseHeaders(200,response.getBytes().length);
    java.io.OutputStream os = t.getResponseBody();
    os.write(response.getBytes());
    os.close();

    logstring( "Response transmitted.");
  }

  private ArrayList<String> getSubTerms(OWLClassExpression exp, OWLReasoner r, boolean longURI, boolean direct, boolean verbose )
  {
    ArrayList<String> idList = new ArrayList<String>();
    NodeSet<OWLClass> subClasses = r.getSubClasses(exp, direct);

    for ( Node<OWLClass> owlClassNode : subClasses )
      class_to_termlist( owlClassNode, idList, longURI, verbose );

    return idList;
  }

  private ArrayList<String> getSiblings(OWLClassExpression exp, OWLReasoner r, boolean longURI, boolean direct, boolean verbose )
  {
    Set<Node<OWLClass>> parentnodes = r.getSuperClasses( exp, true ).getNodes();
    HashSet<String> sibs = new HashSet<String>();

    ArrayList<String> retval;

    if ( verbose )
      retval = new ArrayList<String>();
    else
      retval = null;

    for ( Node<OWLClass> pnode : parentnodes )
    {
      OWLClass parent = pnode.getRepresentativeElement();
      NodeSet<OWLClass> childnodes = r.getSubClasses( parent, true );

      String plabel = null, pID = null;

      if ( verbose )
      {
        plabel = LabelByClass(parent);

        if ( plabel == null )
          plabel = "null";

        pID = shorturl(parent.getIRI().toString());
      }

      for ( OWLClass c : childnodes.getFlattened() )
      {
        String sibID = shorturl(c.getIRI().toString());

        if ( sibs.contains( sibID ) )
          continue;

        sibs.add( sibID );

        if ( verbose )
        {
          String label = LabelByClass(c);

          if ( label == null )
            label = "null";

          String jsobj = "{\n   \"sibling\":\""+sibID+"\",\n   \"label\":\""+label+"\",\n   \"parent\":\""+pID+"\",\n   \"parent_label\":\""+plabel+"\"\n}";
          retval.add(jsobj);
        }
      }
    }

    if ( verbose )
      return retval;
    else
      return new ArrayList<String>(sibs);
  }

  private ArrayList<String> getInstances(OWLClassExpression exp, OWLReasoner r, boolean verbose)
  {
    ArrayList<String> idList = new ArrayList<String>();
    NodeSet<OWLNamedIndividual> inst = r.getInstances(exp, false);

    for (Node<OWLNamedIndividual> ind : inst)
      individual_to_termlist( ind, idList, false, verbose );

    return idList;
  }

  private ArrayList<String> getEquivalentTerms(OWLClassExpression exp, OWLReasoner r, boolean verbose)
  {
    ArrayList<String> idList = new ArrayList<String>();
    Node<OWLClass> equivalentClasses = r.getEquivalentClasses(exp);

    for ( OWLClass c : equivalentClasses.getEntities() )
      obj_to_termlist( c, idList, false, verbose );

    return idList;
  }

  public String LabelByClass(OWLEntity c)
  {
    for ( OWLOntology imp : imp_closure )
    {
      Set<OWLAnnotation> annots = c.getAnnotations( imp, rdfslab );

      for ( OWLAnnotation a : annots )
      {
        if ( a.getValue() instanceof OWLLiteral )
          return ((OWLLiteral)a.getValue()).getLiteral();
      }
    }

    return null;
  }

  public ArrayList<String> getLabels(String shortform, OWLOntology o )
  {
    OWLEntity e = shorts.getEntity(shortform);

    if ( e == null || !e.isOWLClass() )
      return null;

    ArrayList<String> idList = new ArrayList<String>();

    Set<OWLAnnotation> annots = e.getAnnotations(o, rdfslab );

    if ( annots.isEmpty() )
    {
      for ( OWLOntology imp : imp_closure )
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

  public ArrayList<String> SearchByLabel(String label, OWLOntology o, boolean verbose )
  {
    Set<OWLEntity> ents = annovider.getEntities(label);

    if ( ents == null || ents.isEmpty() )
      return null;

    ArrayList<String> idList = new ArrayList<String>();

    for ( OWLEntity e : ents )
    {
      if ( e.isOWLClass() && !e.asOWLClass().isAnonymous() )
        obj_to_termlist( e, idList, false, verbose );
    }

    return idList;
  }

  public ArrayList<String> getTerms(OWLClassExpression exp, OWLReasoner r, boolean verbose)
  {
    ArrayList<String> idList = new ArrayList<String>();

    idList.addAll(getEquivalentTerms(exp,r,verbose));
    idList.addAll(getSubTerms(exp,r,false,false,verbose));

    return idList;
  }

  public ArrayList<String> addTerm(OWLClassExpression exp, OWLReasoner r, OWLOntologyManager mgr, OWLOntology ont, IRI iri, boolean verbose )
  {
    ArrayList<String> idList = getEquivalentTerms(exp,r,verbose);
    if(idList.isEmpty())
    {
      String ricordoid = String.valueOf(System.currentTimeMillis());
      OWLClass newowlclass = df.getOWLClass(IRI.create(kbNs + ricordoid));

      mgr.addAxiom(ont, df.getOWLEquivalentClassesAxiom(newowlclass, exp) );

      if ( rname.equals("elk") )
        r.flush();

      maybe_save_ontology( ont, iri, mgr );

      obj_to_termlist( newowlclass, idList, false, verbose );
      r.precomputeInferences(InferenceType.CLASS_HIERARCHY);
    }

    return idList;
  }

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

  public String compute_response( ArrayList<String> terms, boolean fJson, boolean longURI, boolean verbose )
  {
    StringBuilder x = new StringBuilder();

    if ( verbose )
    {
      boolean fFirst = false;
      x.append("{\n \"results\":\n [\n  " );

      for ( String js : terms )
      {
        if ( !fFirst )
          fFirst = true;
        else
          x.append( ",\n  " );

        x.append( js );
      }

      x.append("\n ]\n}");

      return x.toString();
    }

    if ( fJson )
    {
      x.append("[\n ");
      boolean fFirst = false;

      for ( String termp : terms )
      {
        if ( !fFirst )
          fFirst = true;
        else
          x.append(",\n ");

        x.append( "\"" + (longURI ? termp : shorturl(termp)) +"\"" );
      }
      x.append("]");
    }
    else
    {
      x.append("<table><tr><th>ID</th></tr>");

      for ( String termp : terms )
        x.append("<tr><td>" + (longURI ? termp : shorturl(termp)) +"</td></tr>");

      x.append("</table>");
    }

    return x.toString();
  }

  public void init_owlkb( String [] args )
  {
    df = OWLManager.getOWLDataFactory();
    rdfslab = df.getRDFSLabel();

    parse_commandline_arguments(args);
  }

  public void parse_commandline_arguments( String [] args )
  {
    rname = "elk";
    hd_save = true;
    kbNs = "http://www.ricordo.eu/ricordo.owl#RICORDO_";
    kbfilename = "/home/sarala/testkb/ricordo.owl";  // Keep this silly default for backward compatibility
    sparql = null;
    help_only = false;
    port = 20080;
    get_counts_from_feather = false;

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
        System.out.println( "-sparql <url base>"                                    );
        System.out.println( "(Base of URL to use as SPARQL endpoint, to allow"      );
        System.out.println( " interaction with a triple store.)"                    );
        System.out.println( "(Default: null)"                                       );
        System.out.println( "------------------------------------"                  );
        System.out.println( "-help"                                                 );
        System.out.println( "(Displays this helpfile)"                              );
        System.out.println( "" );
        help_only = true;
        return;
      }

      if ( flag.equals("port") || flag.equals("p") )
      {
        if ( i+1 < args.length )
        {
          try
          {
            port = Integer.parseInt(args[i+1]);
          }
          catch( Exception e )
          {
            System.out.println( "Port must be a number." );
            help_only = true;
            return;
          }
          System.out.println( "Owlkb will listen on port "+args[++i] );
        }
        else
        {
          System.out.println( "Which port do you want the server to listen on?" );
          help_only = true;
          return;
        }
      }
      else if ( flag.equals("rname") || flag.equals("reasoner") )
      {
        if ( i+1 < args.length && (args[i+1].equals("elk") || args[i+1].equals("hermit")) )
        {
          rname = args[++i];
          System.out.println( "Using "+rname+" as reasoner" );
        }
        else
        {
          System.out.println( "Valid reasoners are: ELK, HermiT" );
          help_only = true;
          return;
        }
      }
      else if ( flag.equals("hd") || flag.equals("hd_save") || flag.equals("save") )
      {
        if ( i+1 < args.length && (args[i+1].equals("t") || args[i+1].equals("true")) )
          hd_save = true;
        else if ( i+1 < args.length && (args[i+1].equals("f") || args[i+1].equals("false")) )
        {
          hd_save = false;
          System.out.println( "Saving changes to hard drive: disabled." );
        }
        else
        {
          System.out.println( "hd_save can be set to: true, false" );
          help_only = true;
          return;
        }
        i++;
      }
      else if ( flag.equals( "uclsyntax" ) || flag.equals("ucl-syntax") || flag.equals("ucl_syntax") )
      {
        if ( i+1 < args.length && (args[i+1].equals("t") || args[i+1].equals("true")) )
        {
          /*
           * Backwards compatibility (originally uclsyntax was a boolean and open-physiology was hardcoded as the endpoint
           */
          ucl_syntax = "http://open-physiology.org:5052/uclsyntax/";
          System.out.println( "UCL Syntax: endpoint set to http://open-physiology.org:5052/uclsyntax/" );
        }
        else if ( i+1 < args.length && (args[i+1].equals("f") || args[i+1].equals("false")) )
          ucl_syntax = null;
        else if ( i+1 < args.length )
        {
          ucl_syntax = args[i+1];
          System.out.println( "UCL Syntax: endpoint set to " + ucl_syntax );
        }
        else
        {
          System.out.println( "uclsyntax can be set to: true, false" );
          help_only = true;
          return;
        }
        i++;
      }
      else if (flag.equals("kbns") || flag.equals("ns") || flag.equals("namespace") )
      {
        if ( i+1 < args.length )
        {
          System.out.println( "Using "+args[i+1]+" as ontology namespace." );
          kbNs = args[++i];
        }
        else
        {
          System.out.println( "What do you want the ontology's namespace to be?" );
          System.out.println( "Default: http://www.ricordo.eu/ricordo.owl#RICORDO_" );
          help_only = true;
          return;
        }
      }
      else if ( flag.equals("sparql") )
      {
        if ( i+1 < args.length )
        {
          System.out.println( "Using "+args[i+1]+" as base of URL of SPARQL endpoint." );
          sparql = args[++i];
        }
        else
        {
          System.out.println( "Specify the base of the URL of the SPARQL endpoint you want to use." );
          System.out.println( "Default: null (in which case OWLKB will not interact with SPARQL)" );
          help_only = true;
          return;
        }
      }
      else if ( flag.equals("kbfilename") || flag.equals("filename") || flag.equals("kbfile") || flag.equals("file") || flag.equals("kb") )
      {
        if ( i+1 < args.length )
        {
          System.out.println( "Using "+args[i+1]+" as ontology filename." );
          kbfilename = args[++i];
        }
        else
        {
          System.out.println( "Specify the filename of the ontology." );
          help_only = true;
          return;
        }
      }
      else
      {
        System.out.println( "Unrecognized command line argument: "+args[i] );
        System.out.println( "For help, run with HELP as command line argument." );
        help_only = true;
        return;
      }
    }
  }

  public void send_gui(HttpExchange t)
  {
    String the_html, the_js;

    try
    {
      the_html = readfile("gui.html");
      the_js = readfile("gui.js");

      if ( the_html == null || the_js == null )
      {
        send_response( t, "The GUI could not be sent, due to a problem with the html file or the javascript file.", false );
        return;
      }

      the_html = the_html.replace("@JAVASCRIPT", "<script type='text/javascript'>"+the_js+"</script>");

      send_response( t, the_html, false );
    }
    catch(Exception e)
    {
      ;
    }
  }

  public String compute_addlabel_response( OWLOntology o, IRI ontology_iri, OWLOntologyManager m, String req, boolean fJson )
  {
    int eqpos = req.indexOf('=');

    if ( eqpos == -1 || eqpos == 0 )
    {
      if ( fJson )
        return "{'syntax error'}";
      else
        return "Invalid syntax.  Syntax: /addlabel/iri=label, e.g.: /addlabel/RICORDO_123=volume of blood";
    }

    String iri = req.substring(0,eqpos);
    String label = req.substring(eqpos+1);

    if ( iri.length() < "RICORDO_".length() || !iri.substring(0,"RICORDO_".length()).equals("RICORDO_") )
    {
      if ( fJson )
        return "{'non-ricordo class error'}";
      else
        return "Only RICORDO classes can have labels added to them through OWLKB.";
    }

    if ( label.trim().equals("") )
    {
      if ( fJson )
        return "{'blank label error'}";
      else
        return "Blank labels are not allowed.";
    }

    OWLEntity e = shorts.getEntity(iri);

    if ( e == null || !e.isOWLClass() )
    {
      if ( fJson )
        return "{'class not found error'}";
      else
        return "The specified class could not be found.  Please make sure you're using the shortform of the iri, e.g., RICORDO_123 instead of http://website.com/RICORDO_123";
    }

    Set<OWLAnnotation> annots = e.getAnnotations(o, rdfslab );

    if ( !annots.isEmpty() )
    {
      for ( OWLAnnotation a : annots )
      {
        if ( a.getValue() instanceof OWLLiteral )
        {
          if ( ((OWLLiteral)a.getValue()).getLiteral().equals(label) )
            return fJson ? "{'ok'}" : "Class "+iri+" now has label "+escapeHTML(label);
        }
      }
    }

    IRI rdfslab_iri = org.semanticweb.owlapi.vocab.OWLRDFVocabulary.RDFS_LABEL.getIRI();
    OWLAnnotation a = df.getOWLAnnotation( df.getOWLAnnotationProperty(rdfslab_iri), df.getOWLLiteral(label) );
    OWLAxiom axiom = df.getOWLAnnotationAssertionAxiom(e.asOWLClass().getIRI(), a);
    m.applyChange(new AddAxiom( o, axiom ));
    logstring( "Added rdfs:label "+label+" to class "+iri+"." );

    maybe_save_ontology( o, ontology_iri, m );

    return fJson ? "{'ok'}" : "Class "+iri+" now has label "+escapeHTML(label);
  }

  public void maybe_save_ontology( OWLOntology ont, IRI iri, OWLOntologyManager m )
  {
    if ( hd_save )
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

  public String compute_rdfstore_response( OWLOntology o, IRI iri, OWLOntologyManager m, OWLEntityChecker ec, OWLReasoner r, String req )
  {
    String x = full_iri_from_full_or_short_iri( req, o );

    if ( x != null )
      return x;

    OWLClassExpression exp = parse_manchester( req, o, ec );

    if ( exp != null )
    {
      ArrayList<String> terms = getSubTerms(exp, r, true, false, false);

      return compute_response( terms, true, true, false );
    }

    return req;
  }

  public String compute_subgraph_response( OWLOntology o, IRI iri, OWLOntologyManager m, OWLReasoner reasoner, String req )
  {
    req = "," + req.replace("fma:", "http://purl.org/obo/owlapi/fma%23FMA_");

    String feather_response = queryFeather("subgraph", req);

    if ( feather_response == null )
      return "?";
    else
      return feather_response;
  }

  public String compute_shortestpath_response( OWLOntology o, IRI iri, OWLOntologyManager m, OWLReasoner reasoner, String req )
  {
    req = req.replace("fma:", "http://purl.org/obo/owlapi/fma%23FMA_");
    String feather_response = queryFeather("shortpath", req);

    if ( feather_response == null )
      return "?";
    else
      return feather_response;
  }

  public String compute_generate_triples_response( OWLOntology o, IRI iri, OWLOntologyManager m, OWLReasoner reasoner, String req )
  {
    OWLReasoner r = reasoner;
    java.io.PrintWriter writer;

    try
    {
      writer = new java.io.PrintWriter( "triples.nt", "UTF-8" );
    }
    catch( Exception e )
    {
      return "{ \"error\": \"Could not open triples.nt for writing\" }";
    }

    for ( OWLOntology ont : imp_closure )
    {
      Set<OWLClass> classes = ont.getClassesInSignature();

      for ( OWLClass c : classes )
      {
        NodeSet<OWLClass> subClasses = r.getSubClasses(c, true);
        String cString = c.toStringID();

        Set<OWLIndividual> inds = c.getIndividuals(ont);

        for ( OWLIndividual ind : inds )
        {
          if ( !(ind instanceof OWLNamedIndividual) )
            continue;

          String iString = ind.asOWLNamedIndividual().getIRI().toString();

          if ( iString.equals("") )
            continue;

          writer.println( "<" + iString + "> <http://open-physiology.org/#super-or-equal> <" + iString + "> ." );

          if ( !cString.equals("") )
            writer.println( "<" + cString + "> <http://open-physiology.org/#super-or-equal> <" + iString + "> ." );
        }

        if ( cString.equals("") )
          continue;

        writer.println( "<" + cString + "> <http://open-physiology.org/#super-or-equal> <" + cString + "> ." );

        for ( Node<OWLClass> subnode : subClasses )
        {
          OWLClass sub = subnode.getEntities().iterator().next();

          if ( sub.isOWLNothing() )
            continue;

          writer.println( "<" + cString + "> <http://open-physiology.org/#super-or-equal> <" + sub.toStringID() + "> ." );
        }

        Set<OWLClassExpression> supers = c.getSuperClasses(ont);
        for ( OWLClassExpression exp : supers )
        {
          if ( !(exp instanceof OWLObjectSomeValuesFrom) )
            continue;

          OWLRestriction restrict = (OWLRestriction) exp;

          Set<OWLObjectProperty> objprops = restrict.getObjectPropertiesInSignature();
          boolean fBad = false;

          for ( OWLObjectProperty objprop : objprops )
          {
            if ( !objprop.toStringID().equals( "http://purl.org/obo/owlapi/fma#regional_part_of" )
            &&   !objprop.toStringID().equals( "http://purl.org/obo/owlapi/fma#constitutional_part_of" ) )
            {
              fBad = true;
              break;
            }
          }
          if ( fBad )
            continue;

          Set<OWLClass> classes_in_signature = restrict.getClassesInSignature();

          for ( OWLClass class_in_signature : classes_in_signature )
          {
            writer.println( "<" + class_in_signature.toStringID() + "> <http://open-physiology.org/#super-or-equal> <" + cString + "> ." );
            break;
          }
        }
      }
    }

    writer.close();

    return "{ \"result\": \"Triples saved to file triples.nt in owlkb directory\" }";
  }

  public String compute_subhierarchy_response( OWLClassExpression exp, OWLReasoner r )
  {
    StringBuilder sb = new StringBuilder();

    sb.append( "{\n" );
    append_subhierarchy( sb, exp, r, 1 );
    sb.append( "\n}" );

    return sb.toString();
  }

  public void append_subhierarchy( StringBuilder sb, OWLClassExpression exp, OWLReasoner r, int indent )
  {
    append_spaces( sb, indent );
    sb.append( "\"subterms\":\n" );
    append_spaces( sb, indent );
    sb.append( "[\n" );

    Set<Node<OWLClass>> nodes = r.getSubClasses( exp, true ).getNodes();
    boolean isFirst = true;

    for ( Node<OWLClass> node : nodes )
    {
      OWLClass c = node.getRepresentativeElement();

      if ( c.isOWLNothing() )
        continue;

      if ( isFirst )
        isFirst = false;
      else
        sb.append( ",\n" );

      append_spaces( sb, indent + 1 );
      sb.append( "{\n" );
      append_spaces( sb, indent + 2 );
      sb.append( "\"term\": \"" + shorturl(c.getIRI().toString()) + "\",\n" );

      String label = LabelByClass( c );
      if ( label != null )
      {
        append_spaces( sb, indent + 2 );
        sb.append( "\"label\": \"" + escapeJSON(label) + "\",\n" );
      }

      append_subhierarchy( sb, c, r, indent+2 );

      sb.append( "\n" );
      append_spaces( sb, indent + 1 );
      sb.append( "}" );
    }

    sb.append( "\n" );
    append_spaces( sb, indent );
    sb.append( "]" );
  }

  public void append_spaces( StringBuilder sb, int n )
  {
    sb.append( String.format( "%"+n+"s", "" ) );
  }

  public String compute_apinatomy_response( OWLOntology o, IRI iri, OWLOntologyManager m, OWLReasoner reasoner, String req )
  {
    String response = "[\n";
    boolean isFirstResult = true;

    req = req.replace("fma:", "FMA_");

    List<String> shortforms = java.util.Arrays.asList(req.split(","));

    /*
     * Max size chosen based on FMA's most prolific class, FMA_21792 ("Fascia of muscle"), which has 222 subs
     */
    if ( shortforms.size() > 250 )
      return "[]";

    if ( req.substring(0,6).equals( "24tile" ) )
    {
      String top24 = readfile("24tiles.dat");

      return (top24 != null) ? top24 : "[]";
    }

    if ( req.equals("pkpd_base") )
    {
      String toptiles = readfile("pkpdroot.dat");

      return (toptiles != null) ? toptiles : "[]";
    }

    for ( String shortform : shortforms )
    {
      OWLEntity e = shorts.getEntity(shortform);

      if ( e == null || (!e.isOWLClass() && !e.isOWLNamedIndividual() ) )
        continue;

      if ( isFirstResult )
        isFirstResult = false;
      else
        response += ",\n";

      response += "  {\n    \"_id\": \"" + escapeJSON(shortform) + "\",\n";

      String the_label = get_one_rdfs_label( e, o );

      if ( the_label == null )
        the_label = shortform;

      response += "    \"name\": \"" + escapeJSON(the_label) + "\",\n    \"sub\":\n    [\n";

      if ( e.isOWLClass() )
      {
        List<Apinatomy_Sub> subs = get_apinatomy_subs(e, reasoner, o);
        boolean isFirstSub = true;

        for ( Apinatomy_Sub sub : subs )
        {
          if ( isFirstSub )
            isFirstSub = false;
          else
            response += ",\n";

          response += "      {\n        \"type\": \"" + escapeJSON(sub.type) + "\",\n";
          response += "        \"entity\":\n        {\n          \"_id\": \"" + escapeJSON(sub.id) + "\"\n        }\n      }";
        }
      }

      response += "\n    ]\n  }";
    }

    response = response.replace( "FMA_", "fma:" );

    response += "\n]";

    return response;
  }

  public String queryURL(String urlstring)
  {
    StringBuilder buf = null;
    java.io.Reader r = null;

    try
    {
      java.net.URL url = new java.net.URL(urlstring);
      java.net.URLConnection con = url.openConnection();

      /*
       * To do: Make these values configurable
       */
      con.setConnectTimeout( 1000 );
      con.setReadTimeout( 1000 );

      r = new java.io.InputStreamReader(con.getInputStream(), "UTF-8");
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

  public String query_sparql_for_count(String x)
  {
    StringBuilder sb = new StringBuilder();
    sb.append( "SELECT COUNT(*) WHERE " );
    sb.append( "{ " );
      sb.append( "?s ?p ?o . " );

      if ( x.length() >= 3 && x.substring(0,3).toLowerCase().equals("fma") )
        sb.append( "<http://purl.org/obo/owlapi/fma#"+x+"> <http://open-physiology.org/#super-or-equal> ?o " );
      else if ( x.length() >= 4 && x.substring(0,4).toLowerCase().equals("pkpd") )
        sb.append( "<http://www.ddmore.org/ontologies/ontology/pkpd-ontology#"+x+"> <http://open-physiology.org/#super-or-equal> ?o " );
      else
        sb.append( "<"+x+"> <http://open-physiology.org/#super-or-equal> ?o " );

      sb.append( "FILTER( ?p != <http://open-physiology.org/#super-or-equal> )" );
    sb.append( "}" );

    String sparqlcode = sb.toString();

    try
    {
      sparqlcode = URLEncode(sparqlcode);
    }
    catch(Exception e)
    {
      ;
    }

    String s = queryURL(sparql + sparqlcode);

    int pos = s.indexOf( "\"value\": \"" );

    if ( pos == -1 )
      return null;

    s = s.substring( pos + "\"value\": \"".length() );

    pos = s.indexOf( '\"' );

    if ( pos == -1 )
      return null;

    return s.substring( 0, pos );
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

  public List<Apinatomy_Sub> get_apinatomy_subs( OWLEntity e, OWLReasoner r, OWLOntology o )
  {
    List<Apinatomy_Sub> response = new ArrayList<Apinatomy_Sub>();

    if ( !( e instanceof OWLClass ) )
      return response;

    OWLClass c = e.asOWLClass();

    ArrayList<String> subclasslist = getSubTerms(c, r, false, true, false);

    for ( String subclass : subclasslist )
    {
      String the_id = shorturl( subclass );

      if ( !the_id.equals( "Nothing" ) )
        response.add( new Apinatomy_Sub( the_id, "subclass" ) );
    }

    for ( OWLOntology imp : imp_closure )
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

  public String get_one_rdfs_label( OWLEntity e, OWLOntology o )
  {
    Set<OWLAnnotation> annots = e.getAnnotations(o, rdfslab);

    if ( annots.isEmpty() )
    {
      for ( OWLOntology imp : imp_closure )
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

  public static String escapeJSON(String s)
  {
    int len = s.length();
    StringBuilder sb = new StringBuilder(len);

    for ( int i = 0; i < len; i++ )
    {
      switch( s.charAt(i) )
      {
        case '\\':
          sb.append( "\\\\" );
          break;
        case '\"':
          sb.append( "\\\"" );
          break;
        default:
          sb.append( s.charAt(i) );
          break;
      }
    }

    return sb.toString();
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

    for (String param : query.split("&"))
    {
      String pair[] = param.split("=");
      if (pair.length > 1)
        result.put(URLDecode(pair[0]), URLDecode(pair[1]));
      else
        result.put(URLDecode(pair[0]), "");
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

  private void class_to_termlist( Node<OWLClass> node, List<String> L, boolean longIRI, boolean verbose )
  {
    obj_to_termlist( node.getRepresentativeElement(), L, longIRI, verbose );
  }

  private void individual_to_termlist( Node<OWLNamedIndividual> node, List<String> L, boolean longIRI, boolean verbose )
  {
    obj_to_termlist( node.getRepresentativeElement(), L, longIRI, verbose );
  }

  private void obj_to_termlist( OWLEntity c, List<String> L, boolean longIRI, boolean verbose )
  {
    String the_iri = longIRI ? c.getIRI().toString() : c.toStringID();

    if ( verbose )
    {
      StringBuilder sb = new StringBuilder();

      sb.append( "{\n \"term\": \"" );
      sb.append( the_iri );
      sb.append( "\",\n \"label\": " );

      String label = LabelByClass(c);

      if ( label == null )
        sb.append( "null" );
      else
        sb.append( "\"" + escapeJSON(label) + "\"" );

      sb.append( "\n}" );

      L.add(sb.toString());
    }
    else
      L.add(the_iri);
  }

  String compute_demo_response( OWLClassExpression exp, OWLReasoner r, OWLOntologyManager m, OWLOntology o, IRI iri, long start_time, boolean fJson, boolean verbose )
  {
    ArrayList<String> terms;

    try
    {
      terms = addTerm( exp, r, m, o, iri, verbose );
    }
    catch( Exception e )
    {
      return "Malformed Manchester query";
    }

    StringBuilder sb = new StringBuilder();

    if ( fJson )
    {
      sb.append( "{\"terms\": [" );

      if ( verbose )
        sb.append( jsobjs_to_csv( terms ) );
      else
        sb.append( terms_to_csv( terms ) );

      sb.append( "]," );
    }
    else
    {
      sb.append( "<h3>Term</h3><ul>" );

      for ( String termp : terms )
        sb.append( "<li>" + shorturl(termp) + "</li>" );

      sb.append( "</ul>" );
    }

    ArrayList<String> subs;

    try
    {
      subs = getSubTerms(exp,r,false,false,verbose);
    }
    catch( Exception e )
    {
      return "There was an error getting the results";
    }

    if ( fJson )
    {
      sb.append( "\"subterms\": [" );

      if ( verbose )
        sb.append( jsobjs_to_csv( subs ) );
      else
        sb.append( terms_to_csv( subs ) );

      sb.append( "]," );
    }
    else if ( subs.size() != 0 )
    {
      sb.append( "<h3>Subterms</h3><ul>" );

      for ( String termp : subs )
        sb.append( "<li>" + shorturl(termp) + "</li>" );

      sb.append( "</ul>" );
    }

    if ( fJson )
      sb.append( "\"runtime\": \"" + (System.nanoTime() - start_time) / 1000000 + "ms\"}" );
    else
      sb.append( "<h5>Runtime</h5>This computation took "+(System.nanoTime() - start_time) / 1000000+"ms to complete" );

    return sb.toString();
  }

  String terms_to_csv( List<String> terms )
  {
    StringBuilder sb = new StringBuilder();
    boolean fFirst = false;

    for ( String term : terms )
    {
      if ( !fFirst )
        fFirst = true;
      else
        sb.append( "," );

      sb.append( "\"" + shorturl(term) + "\"" );
    }

    return sb.toString();
  }

  String jsobjs_to_csv( List<String> jsobjs )
  {
    StringBuilder sb = new StringBuilder();
    boolean fFirst = false;

    for ( String jsobj : jsobjs )
    {
      if ( !fFirst )
        fFirst = true;
      else
        sb.append(",");

      sb.append(jsobj);
    }

    return sb.toString();
  }

  static String URLDecode(String x)
  {
    try
    {
      return java.net.URLDecoder.decode(x, "UTF-8");
    }
    catch( Exception e )
    {
      return null;
    }
  }

  static String URLEncode(String x)
  {
    try
    {
      return java.net.URLEncoder.encode(x,"UTF-8");
    }
    catch( Exception e )
    {
      return null;
    }
  }

  static String readfile(String filename)
  {
    try
    {
      return new java.util.Scanner(new File(filename)).useDelimiter("\\A").next();
    }
    catch(Exception e)
    {
      return null;
    }
  }
}

