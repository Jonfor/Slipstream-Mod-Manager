package net.vhati.modmanager.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.vhati.modmanager.core.SloppyXMLParser;

import org.jdom2.Attribute;
import org.jdom2.Content;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.Namespace;
import org.jdom2.filter.AbstractFilter;
import org.jdom2.filter.ElementFilter;
import org.jdom2.filter.Filter;
import org.jdom2.input.JDOMParseException;
import org.jdom2.input.SAXBuilder;


/**
 * Programmatically edits existing XML with instructions from another XML doc.
 * Other tags are simply appended as-is.
 */
public class XMLPatcher {

	protected boolean globalPanic = false;
	protected Namespace modNS;
	protected Namespace modAppendNS;
	protected Namespace modOverwriteNS;
	protected Namespace modPrependNS;
	protected Namespace modInsertNS;


	public XMLPatcher() {
		modNS = Namespace.getNamespace( "mod", "mod" );
		modAppendNS = Namespace.getNamespace( "mod-append", "mod-append" );
		modOverwriteNS = Namespace.getNamespace( "mod-overwrite", "mod-overwrite" );
		modPrependNS = Namespace.getNamespace( "mod-prepend", "mod-prepend" );
		modInsertNS = Namespace.getNamespace( "mod-insert", "mod-insert");
	}

	public void setGlobalPanic( boolean b ) {
		globalPanic = b;
	}


	public Document patch( Document mainDoc, Document appendDoc ) {
		Document resultDoc = mainDoc.clone();
		Element resultRoot = resultDoc.getRootElement();
		Element appendRoot = appendDoc.getRootElement();

		ElementFilter modFilter = new ElementFilter( modNS );
		for ( Content content : appendRoot.getContent() ) {
			if ( modFilter.matches( content ) ) {
				Element node = (Element)content;

				boolean handled = false;
				List<Element> matchedNodes = handleModFind( resultRoot, node );
				if ( matchedNodes != null ) {
					handled = true;
					for ( Element matchedNode : matchedNodes ) {
						handleModCommands( matchedNode, node );
					}
				}

				if ( !handled ) {
					throw new IllegalArgumentException( String.format( "Unrecognized mod tag <%s> (%s).", node.getName(), getPathToRoot(node) ) );
				}
			}
			else {
				resultRoot.addContent( content.clone() );
			}
		}

		return resultDoc;
	}


	/**
	 * Returns find results if node is a find tag, or null if it's not.
	 *
	 * An empty list will be returned if there were no matches.
	 *
	 * TODO: Throw an exception in callers if results are required.
	 *
	 * @throws ModFindRegexException <br>
	 * if a find tag has regex="true" but one of its fields has a syntax error as defined by {@link java.util.regex.Pattern}
	 */
	protected List<Element> handleModFind( Element contextNode, Element node ) {
		List<Element> result = null;

		if ( node.getNamespace().equals( modNS ) ) {

			if ( node.getName().equals( "findName" ) ) {

				String searchName = node.getAttributeValue( "name" );
				String searchType = node.getAttributeValue( "type" );
				boolean searchReverse = getAttributeBooleanValue( node, "reverse", true );
				int searchStart = getAttributeIntValue( node, "start", 0 );
				int searchLimit = getAttributeIntValue( node, "limit", 1 );
				boolean panic = getAttributeBooleanValue( node, "panic", false );
				if ( globalPanic ) panic = true;
				boolean useRegex = getAttributeBooleanValue( node, "regex", false );

				if ( searchName == null || searchName.length() == 0 )
					throw new IllegalArgumentException( String.format( "<%s> requires a name attribute (%s).", node.getName(), getPathToRoot(node) ) );
				if ( searchType != null && searchType.length() == 0 )
					throw new IllegalArgumentException( String.format( "<%s> type attribute, when present, can't be empty (%s).", node.getName(), getPathToRoot(node) ) );
				if ( searchStart < 0 )
					throw new IllegalArgumentException( String.format( "<%s> 'start' attribute is not >= 0 (%s).", node.getName(), getPathToRoot(node) ) );
				if ( searchLimit < -1 )
					throw new IllegalArgumentException( String.format( "<%s> 'limit' attribute is not >= -1 (%s).", node.getName(), getPathToRoot(node) ) );
	
				Map<String,String> attrMap = new HashMap<String,String>();
				attrMap.put( "name", searchName );
				LikeFilter searchFilter;
				try {
					searchFilter = new LikeFilter( searchType, attrMap, null, useRegex );
				}
				catch ( ModFindRegexException exception ) {
					throw new ModFindRegexException (
							String.format( "Path to problem: %s\n", getPathToRoot(node)),
							exception
					);
				}
	
				List<Element> matchedNodes = new ArrayList<Element>( contextNode.getContent( searchFilter ) );
				if ( searchReverse ) Collections.reverse( matchedNodes );
	
				if ( searchStart < matchedNodes.size() ) {
					if ( searchLimit > -1 ) {
						matchedNodes = matchedNodes.subList( searchStart, Math.min( matchedNodes.size(), searchStart + searchLimit ) );
					} else if ( searchStart > 0 ) {
						matchedNodes = matchedNodes.subList( searchStart, matchedNodes.size() );
					}
				}
				else {
					matchedNodes.clear();
				}
				if ( panic && matchedNodes.isEmpty() )
					throw new NoSuchElementException( String.format( "<%s> was set to require results but found none (%s).", node.getName(), getPathToRoot(node) ) );

				result = matchedNodes;
			}
			else if ( node.getName().equals( "findLike" ) ) {

				String searchType = node.getAttributeValue( "type" );
				boolean searchReverse = getAttributeBooleanValue( node, "reverse", false );
				int searchStart = getAttributeIntValue( node, "start", 0 );
				int searchLimit = getAttributeIntValue( node, "limit", -1 );
				boolean panic = getAttributeBooleanValue( node, "panic", false );
				if ( globalPanic ) panic = true;
				boolean useRegex = getAttributeBooleanValue( node, "regex", false );

				if ( searchType != null && searchType.length() == 0 )
					throw new IllegalArgumentException( String.format( "<%s> type attribute, when present, can't be empty (%s).", node.getName(), getPathToRoot(node) ) );
				if ( searchStart < 0 )
					throw new IllegalArgumentException( String.format( "<%s> 'start' attribute is not >= 0 (%s).", node.getName(), getPathToRoot(node) ) );
				if ( searchLimit < -1 )
					throw new IllegalArgumentException( String.format( "<%s> 'limit' attribute is not >= -1 (%s).", node.getName(), getPathToRoot(node) ) );
	
				Map<String,String> attrMap = new HashMap<String,String>();
				String searchValue = null;
	
				Element selectorNode = node.getChild( "selector", modNS );
				if ( selectorNode != null ) {
					for ( Attribute attr : selectorNode.getAttributes() ) {
						if ( attr.getNamespace().equals( Namespace.NO_NAMESPACE ) ) {
							// Blank element values can't be detected as different from absent values (never null).
							// Forbid "" attributes for consistency. :/
							if ( attr.getValue().length() == 0 )
								throw new IllegalArgumentException( String.format( "<%s> attributes, when present, can't be empty (%s).", selectorNode.getName(), getPathToRoot(selectorNode) ) );

							attrMap.put( attr.getName(), attr.getValue() );
						}
					}
					searchValue = selectorNode.getTextTrim();  // Never null, but often "".
					if ( searchValue.length() == 0 ) searchValue = null;
				}

				LikeFilter searchFilter;
				try {
					searchFilter = new LikeFilter(searchType, attrMap, searchValue, useRegex);
				}
				catch ( ModFindRegexException exception ) {
					throw new ModFindRegexException (
							String.format( "Path to problem: %s\n", getPathToRoot(node)),
							exception
					);
				}
	
				List<Element> matchedNodes = new ArrayList<Element>( contextNode.getContent( searchFilter ) );
				if ( searchReverse ) Collections.reverse( matchedNodes );
	
				if ( searchStart < matchedNodes.size() ) {
					if ( searchLimit > -1 ) {
						matchedNodes = matchedNodes.subList( searchStart, Math.min( matchedNodes.size(), searchStart + searchLimit ) );
					} else if ( searchStart > 0 ) {
						matchedNodes = matchedNodes.subList( searchStart, matchedNodes.size() );
					}
				}
				else {
					matchedNodes.clear();
				}
				if ( panic && matchedNodes.isEmpty() )
					throw new NoSuchElementException( String.format( "<%s> was set to require results but found none (%s).", node.getName(), getPathToRoot(node) ) );
	
				result = matchedNodes;
			}
			else if ( node.getName().equals( "findWithChildLike" ) ) {

				String searchType = node.getAttributeValue( "type" );
				String searchChildType = node.getAttributeValue( "child-type" );
				boolean searchReverse = getAttributeBooleanValue( node, "reverse", false );
				int searchStart = getAttributeIntValue( node, "start", 0 );
				int searchLimit = getAttributeIntValue( node, "limit", -1 );
				boolean panic = getAttributeBooleanValue( node, "panic", false );
				if ( globalPanic ) panic = true;
				boolean useRegex = getAttributeBooleanValue( node, "regex", false );

				if ( searchType != null && searchType.length() == 0 )
					throw new IllegalArgumentException( String.format( "<%s> type attribute, when present, can't be empty (%s).", node.getName(), getPathToRoot(node) ) );
				if ( searchChildType != null && searchChildType.length() == 0 )
					throw new IllegalArgumentException( String.format( "<%s> child-type attribute, when present, can't be empty (%s).", node.getName(), getPathToRoot(node) ) );
				if ( searchStart < 0 )
					throw new IllegalArgumentException( String.format( "<%s> 'start' attribute is not >= 0 (%s).", node.getName(), getPathToRoot(node) ) );
				if ( searchLimit < -1 )
					throw new IllegalArgumentException( String.format( "<%s> 'limit' attribute is not >= -1 (%s).", node.getName(), getPathToRoot(node) ) );
	
				Map<String,String> attrMap = new HashMap<String,String>();
				String searchValue = null;
	
				Element selectorNode = node.getChild( "selector", modNS );
				if ( selectorNode != null ) {
					for ( Attribute attr : selectorNode.getAttributes() ) {
						if ( attr.getNamespace().equals( Namespace.NO_NAMESPACE ) ) {
							// TODO: Forbid "" attributes, because blank value doesn't work?
							attrMap.put( attr.getName(), attr.getValue() );
						}
					}
					searchValue = selectorNode.getTextTrim();  // Never null, but often "".
					if ( searchValue.length() == 0 ) searchValue = null;
				}

				LikeFilter searchChildFilter;
				WithChildFilter searchFilter;
				try {
					searchChildFilter = new LikeFilter( searchChildType, attrMap, searchValue, useRegex );
					searchFilter = new WithChildFilter( searchType, searchChildFilter, useRegex );
				}
				catch ( ModFindRegexException exception ) {
					throw new ModFindRegexException(
							String.format( "Path to problem: %s\n", getPathToRoot(node)),
							exception
					);
				}

				List<Element> matchedNodes = new ArrayList<Element>( contextNode.getContent( searchFilter ) );
				if ( searchReverse ) Collections.reverse( matchedNodes );
	
				if ( searchStart < matchedNodes.size() ) {
					if ( searchLimit > -1 ) {
						matchedNodes = matchedNodes.subList( searchStart, Math.min( matchedNodes.size(), searchStart + searchLimit ) );
					} else if ( searchStart > 0 ) {
						matchedNodes = matchedNodes.subList( searchStart, matchedNodes.size() );
					}
				}
				else {
					matchedNodes.clear();
				}
				if ( panic && matchedNodes.isEmpty() )
					throw new NoSuchElementException( String.format( "<%s> was set to require results but found none (%s).", node.getName(), getPathToRoot(node) ) );
	
				result = matchedNodes;
			}
			else if ( node.getName().equals( "findComposite" ) ) {

				boolean searchReverse = getAttributeBooleanValue( node, "reverse", false );
				int searchStart = getAttributeIntValue( node, "start", 0 );
				int searchLimit = getAttributeIntValue( node, "limit", -1 );
				boolean panic = getAttributeBooleanValue( node, "panic", false );
				if ( globalPanic ) panic = true;

				if ( searchStart < 0 )
					throw new IllegalArgumentException( String.format( "<%s> 'start' attribute is not >= 0 (%s).", node.getName(), getPathToRoot(node) ) );
				if ( searchLimit < -1 )
					throw new IllegalArgumentException( String.format( "<%s> 'limit' attribute is not >= -1 (%s).", node.getName(), getPathToRoot(node) ) );

				Element parNode = node.getChild( "par", modNS );
				if ( parNode == null )
					throw new IllegalArgumentException( String.format( "<%s> requires a <par> tag (%s).", node.getName(), getPathToRoot(node) ) );

				List<Element> matchedNodes = handleModPar( contextNode, parNode );
				if ( searchReverse ) Collections.reverse( matchedNodes );
	
				if ( searchStart < matchedNodes.size() ) {
					if ( searchLimit > -1 ) {
						matchedNodes = matchedNodes.subList( searchStart, Math.min( matchedNodes.size(), searchStart + searchLimit ) );
					} else if ( searchStart > 0 ) {
						matchedNodes = matchedNodes.subList( searchStart, matchedNodes.size() );
					}
				}
				else {
					matchedNodes.clear();
				}
				if ( panic && matchedNodes.isEmpty() )
					throw new NoSuchElementException( String.format( "<%s> was set to require results but found none (%s).", node.getName(), getPathToRoot(node) ) );
	
				result = matchedNodes;
			}
		}

		return result;
	}


	/**
	 * Returns collated find results (and par results, handled recursively), or null if node wasn't a par.
	 *
	 * Unique results from all finds will be combined and sorted in the order they appear under contextNode.
	 */
	protected List<Element> handleModPar( Element contextNode, Element node ) {
		List<Element> result = null;

		if ( node.getNamespace().equals( modNS ) && node.getName().equals( "par" ) ) {
			String parOp = node.getAttributeValue( "op" );

			if ( parOp == null || (!parOp.equals("AND") && !parOp.equals("OR") && !parOp.equals("NOR") && !parOp.equals("NAND")) )
				throw new IllegalArgumentException( String.format( "Invalid \"op\" attribute (%s). Must be 'AND', 'OR', 'NAND', or 'NOR'.", getPathToRoot(node) ) );

			boolean isAnd = (parOp.equals("AND") || parOp.equals("NAND"));
			boolean isOr = (parOp.equals("OR") || parOp.equals("NOR"));
			boolean isNot = (parOp.equals("NOR") || parOp.equals("NAND"));

			Set<Element> candidateSet = new HashSet<Element>();
			boolean firstPass = true;
			for ( Element criteriaNode : node.getChildren() ) {
				List<Element> candidates;
				if ( criteriaNode.getName().equals( "par" ) && criteriaNode.getNamespace().equals( modNS ) ) {
					candidates = handleModPar( contextNode, criteriaNode );
				} else {
					candidates = handleModFind( contextNode, criteriaNode );
					if ( candidates == null )
						throw new IllegalArgumentException( String.format( "Invalid <par> search criteria <%s> (%s). Must be a <find...> or <par>.", criteriaNode.getName(), getPathToRoot( criteriaNode ) ) );
				}

				if ( firstPass ) {
					candidateSet.addAll( candidates );
					firstPass = false;
				} else if ( isOr ) {
					candidateSet.addAll( candidates );
				} else {
					candidateSet.retainAll( candidates );
				}
			}
			if ( isNot ) {
				Set<Element> complementSet = new HashSet<Element>( contextNode.getChildren() );
				complementSet.removeAll( candidateSet );
				candidateSet = complementSet;
			}
			Map<Integer,Element> orderedCandidateMap = new TreeMap<Integer,Element>();
			for ( Element candidate : candidateSet ) {
				int index = contextNode.indexOf( candidate );
				orderedCandidateMap.put(index, candidate );
			}

			List<Element> matchedNodes = new ArrayList<Element>( orderedCandidateMap.values() );

			result = matchedNodes;
		}

		return result;
	}


	/**
	 * Performs child mod-commands under node, against contextNode.
	 *
	 * TODO: Maybe have handleModCommand() returning null when unrecognized,
	 * or an object with flags to continue or stop looping commands at
	 * contextNode (e.g., halting after removeTag).
	 */
	protected void handleModCommands( Element contextNode, Element node ) {

		for ( Element cmdNode : node.getChildren() ) {
			boolean handled = false;

			if ( cmdNode.getNamespace().equals( modNS ) ) {

				// Handle nested finds.
				List<Element> matchedNodes = handleModFind( contextNode, cmdNode );
				if ( matchedNodes != null ) {
					handled = true;
					for ( Element matchedNode : matchedNodes ) {
						handleModCommands( matchedNode, cmdNode );
					}
				}
				else if ( cmdNode.getName().equals( "selector" ) ) {
					handled = true;
					// No-op.
				}
				else if ( cmdNode.getName().equals( "par" ) ) {
					handled = true;
					// No-op.
				}
				else if ( cmdNode.getName().equals( "setAttributes" ) ) {
					handled = true;
					for ( Attribute attrib : cmdNode.getAttributes() ) {
						contextNode.setAttribute( attrib.clone() );
					}
				}
				else if ( cmdNode.getName().equals( "removeAttributes" ) ) {
					handled = true;
					for ( Attribute attrib : cmdNode.getAttributes() ) {
						contextNode.removeAttribute( attrib.getName() );
					}
				}
				else if ( cmdNode.getName().equals( "setValue" ) ) {
					handled = true;
					contextNode.setText( cmdNode.getTextTrim() );
				}
				else if ( cmdNode.getName().equals( "removeTag" ) ) {
					handled = true;
					contextNode.detach();
					break;
				} else if ( cmdNode.getName().equals( "insertByFind" ) ) {
					handled = true;
					handleInsertByFind(contextNode, cmdNode);
				}

			}

			else if ( cmdNode.getNamespace().equals( modAppendNS ) ) {
				// Append cmdNode (sans namespace) to the contextNode.
				handled = true;

				Element newNode = cmdNode.clone();
				newNode.setNamespace( null );
				contextNode.addContent( newNode );
			}

			else if ( cmdNode.getNamespace().equals( modPrependNS ) ) {
				// Prepend cmdNode (sans namespace) to the contextNode.
				handled = true;
				
				Element newNode = cmdNode.clone();
				newNode.setNamespace( null );
				contextNode.addContent( 0, newNode );
			}

			else if ( cmdNode.getNamespace().equals( modOverwriteNS ) ) {
				// Remove the first child with the same type and insert cmdNode at its position.
				// Or just append if nothing was replaced.
				handled = true;

				Element newNode = cmdNode.clone();
				newNode.setNamespace( null );

				Element doomedNode = contextNode.getChild( cmdNode.getName(), null );
				if ( doomedNode != null ) {
					int doomedIndex = contextNode.indexOf( doomedNode );
					doomedNode.detach();
					contextNode.addContent( doomedIndex, newNode );
				}
				else {
					contextNode.addContent( newNode );
				}
			}

			if ( !handled ) {
				throw new IllegalArgumentException( String.format( "Unrecognized mod tag <%s> (%s).", cmdNode.getName(), getPathToRoot( cmdNode ) ) );
			}
		}
	}

	protected void handleInsertByFind(Element contextNode, Element cmdNode) {
		// get type="z" attribute
		String type = cmdNode.getAttributeValue("type");
		if ( type == null )
			throw new IllegalArgumentException( String.format("insertByFind requires a type attribute.\n(path: %s)", getPathToRoot(cmdNode) ) );
		// get auxiliary tags
		List<Element> existingNodes = null;
		Element newNode = null;
		for ( Element child : cmdNode.getChildren() ) {
			if ( child.getNamespace().equals( modNS ) ) {
				existingNodes = handleModFind( contextNode, child );
				if ( existingNodes == null ) // then not a find tag
					throw new IllegalArgumentException( String.format( "insertByFind expected mod:find tag, received mod:%s tag.\n(path: %s)", child.getName(), getPathToRoot(child) ) );
			}
			else if ( child.getNamespace().equals( modInsertNS ) )
				newNode = child.clone();
			else
				throw new IllegalArgumentException( String.format(
						"insertByFind expected mod:%s or mod-insert:%s, got %s.\n(path: %s)",
						child.getName(), child.getName(), child.getQualifiedName(), getPathToRoot(child) )
				);
		}
		if ( existingNodes == null ) // then mod:... missing
			throw new IllegalArgumentException( String.format( "insertByFind is missing mod:find tag.\n(path: %s)", getPathToRoot(cmdNode) ) );
		if ( newNode == null )
			throw new IllegalArgumentException( String.format( "insertByFind is missing mod-insert: tag.\n(path: %s)", getPathToRoot(cmdNode) ) );
		newNode.setNamespace( null );
		// retrieve index. before = lowest index. after = highest index + 1.
		int index;
		boolean empty = existingNodes.isEmpty();
		if ( type.equals( "before" ) ) {
			if ( empty ) { // do prepend
				contextNode.addContent( 0, newNode );
				return;
			}
			Element first = existingNodes.get( 0 );
			index = contextNode.indexOf( first );
		}
		else if ( type.equals( "after" ) ) {
			if ( empty ) { // do append
				contextNode.addContent( newNode );
				return;
			}
			Element last = existingNodes.get( existingNodes.size() - 1 );
			index = contextNode.indexOf( last ) + 1;
		}
		else
			throw new IllegalArgumentException( String.format("insertByFind type attribute is invalid, must be either 'before' or 'after'.\n(path: %s)", getPathToRoot(cmdNode) ) );
		// add new node based on index.
		int numChildren = contextNode.getContentSize();
		if ( numChildren == 0 || index < 0 )
			index = 0;
		if ( index > numChildren - 1 )
			contextNode.addContent( newNode );
		else
			contextNode.addContent( index, newNode );
	}

	/**
	 * Returns a string describing this element's location.
	 *
	 * Example: /root/event(SOME_NAME)/choice/text
	 */
	protected String getPathToRoot( Element node ) {
		StringBuilder buf = new StringBuilder();
		String chunk;
		String tmp;
		while ( node != null ) {
			chunk = "/"+ node.getName();

			tmp = node.getAttributeValue( "name" );
			if ( tmp != null && tmp.length() > 0 )
				chunk += "("+ tmp +")";

			buf.insert( 0, chunk );
			node = node.getParentElement();
		}
		return buf.toString().replaceFirst("wrapper", "root");
	}


	/**
	 * Returns the boolean value of an attribute, or a default when the attribute is null.
	 * Only 'true' and 'false' are accepted.
	 */
	protected boolean getAttributeBooleanValue( Element node, String attrName, boolean defaultValue ) {
		String tmp = node.getAttributeValue( attrName );
		if ( tmp == null ) return defaultValue;

		if ( tmp.equals( "true" ) ) {
			return true;
		} else if ( tmp.equals( "false" ) ) {
			return false;
		} else {
			throw new IllegalArgumentException( String.format( "Invalid boolean attribute \"%s\" (%s). Must be 'true' or 'false'.", attrName, getPathToRoot( node ) ) );
		}
	}

	/**
	 * Returns the int value of an attribute, or a default when the attribute is null.
	 */
	protected int getAttributeIntValue( Element node, String attrName, int defaultValue ) {
		String tmp = node.getAttributeValue( attrName );
		if ( tmp == null ) return defaultValue;
		try {
			return Integer.parseInt( tmp );
		}
		catch ( NumberFormatException e ) {
			throw new IllegalArgumentException( String.format( "Invalid int attribute \"%s\" (%s).", attrName, getPathToRoot( node ) ) );
		}
	}

	/**
	 * Indicates a problem occurred with regular expression syntax in mod find tags. <br>
	 * Messages part of the stack trace can be retrieved by {@link ModFindRegexException#getLocalizedMessage()}.
	 */
	public static class ModFindRegexException extends IllegalArgumentException {

		private final String representation;

		/**
		 * Constructs an exception with the specified message and cause, just like the
		 * {@link java.lang.IllegalArgumentException#IllegalArgumentException(String, Throwable) superclass constructor},
		 * except that it also internally saves the messages part of the stack trace for later retrieval by
		 * {@link ModFindRegexException#getLocalizedMessage()}.
		 */
		public ModFindRegexException(String message, Throwable cause) {
			super(message, cause);
			representation = message + cause.getLocalizedMessage();
		}

		/**
		 * Returns a String representation of the messages part of the stack trace.
		 */
		@Override
		public String getLocalizedMessage() {
			return representation;
		}
	}

	/**
	 * Matches elements with equal type/attributes/value.
	 * Null args are ignored. A blank type or value arg is ignored.
	 * All given attributes must be present on a candidate to match.
	 * Attribute values in the map must not be null.
	 */
	protected static class LikeFilter extends AbstractFilter<Element> {
		private String type = null;
		private Map<String,String> attrMap = null;
		private String value = null;
		private Pattern typePattern;
		private Map<String,Pattern> attrToPattern;
		private Pattern valuePattern;

		public LikeFilter( String type, Element selectorNode ) {
			this.type = type;

			if ( selectorNode.hasAttributes() ) {
				this.attrMap = new HashMap<String,String>();
				for ( Attribute attr : selectorNode.getAttributes() ) {
					attrMap.put( attr.getName(), attr.getValue() );
				}
			}

			this.value = selectorNode.getTextTrim();
			if ( this.value.length() == 0 ) this.value = null;
		}

		/**
		 * @throws ModFindRegexException <br>
		 * if {@code regex} is true and {@code type}, any of the values in {@code attrMap}, or {@code value}
		 * has invalid syntax for a regular expression as defined by {@link java.util.regex.Pattern}
		 */
		public LikeFilter( String type, Map<String,String> attrMap, String value, boolean regex ) {
			super();
			if ( type != null && type.length() == 0 ) type = null;
			if ( value != null && value.length() == 0 ) value = null;

			this.type = type;
			this.attrMap = attrMap;
			this.value = value;
			if ( regex ) {
				if ( type != null ) {
					typePattern = getPattern( "type or child-type", type );
				}
				if ( attrMap != null ) {
					attrToPattern = new HashMap<String,Pattern>();
					for ( Map.Entry<String,String> entry : attrMap.entrySet() ) {
						String attribute = entry.getKey();
						Pattern pattern = getPattern( attribute + " attribute", entry.getValue() );
						attrToPattern.put( attribute, pattern );
					}
				}
				if ( value != null ) {
					valuePattern = getPattern( "selector tag value", value );
				}
			}
		}

		/**
		 * Attempts to compile the given {@code pattern} into a regular expression.
		 * @throws ModFindRegexException <br>
		 * if {@code pattern} has invalid syntax as defined by {@link java.util.regex.Pattern}.
		 * {@code location} details the context of {@code pattern}.
		 */
		protected static Pattern getPattern( String location, String pattern ) {
			try {
				return Pattern.compile( pattern );
			} catch ( PatternSyntaxException pse ) {
				String locationDescription = String.format( "Regular expression syntax error...\nCheck %s at listed path.\n", location );
				throw new ModFindRegexException( locationDescription, pse );
			}
		}

		protected static boolean regexNotMatched( String text, Pattern regularExpression ) {
			return !regularExpression.matcher( text ).matches();
		}

		@Override
		public Element filter( Object content ) {
			if ( !(content instanceof Element) ) return null;
			Element node = (Element)content;

			if ( typePattern != null ) {
				if ( regexNotMatched( node.getName(), typePattern ) ) {
					return null;
				}
			} else if ( type != null ) { // regex is false
				if ( !type.equals( node.getName() ) ) {
					return null;
				}
			}

			if ( attrToPattern != null ) {
				String nodeAttrValue;
				for ( Map.Entry<String,Pattern> entry : attrToPattern.entrySet() ) {
					nodeAttrValue = node.getAttributeValue( entry.getKey() );
					if ( nodeAttrValue == null || regexNotMatched( nodeAttrValue, entry.getValue() ) ) {
						return null;
					}
				}
			} else if ( attrMap != null ) { // regex is false
				String nodeAttrValue;
				for ( Map.Entry<String,String> entry : attrMap.entrySet() ) {
					String attrName = entry.getKey();
					String attrValue = entry.getValue();
					nodeAttrValue = node.getAttributeValue( attrName );
					if ( !attrValue.equals( nodeAttrValue ) ) {
						return null;
					}
				}
			}

			if ( valuePattern != null ) {
				if ( regexNotMatched( node.getTextTrim(), valuePattern ) ) {
					return null;
				}
			} else if ( value != null ) { // regex is false
				if ( !value.equals( node.getTextTrim() ) ) {
					return null;
				}
			}
			return node;
		}
	}

	/**
	 * Matches elements with child elements that match a filter.
	 * If the filter is null, matches all elements with children.
	 */
	protected static class WithChildFilter extends AbstractFilter<Element> {
		private String type;
		private Filter<Element> childFilter;
		private Pattern typePattern;

		public WithChildFilter( Filter<Element> childFilter ) {
			this( null, childFilter, false );
		}

		/**
		 * @throws ModFindRegexException <br>
		 * if {@code regex} is true and {@code type} has invalid syntax
		 * for a regular expression as defined by {@link java.util.regex.Pattern}
		 */
		public WithChildFilter( String type, Filter<Element> childFilter, boolean regex ) {
			this.type = type;
			this.childFilter = childFilter;
			if ( regex && type != null ) {
				this.typePattern = LikeFilter.getPattern("find tag type", type );
			}
		}

		@Override
		public Element filter( Object content ) {
			if ( !(content instanceof Element) ) return null;
			Element node = (Element)content;

			if ( typePattern != null ) {
				if ( LikeFilter.regexNotMatched( node.getName(), typePattern ) ) {
					return null;
				}
			} else if ( type != null ) { // regex is false
				if ( !type.equals( node.getName() ) ) {
						return null;
				}
			}

			if ( childFilter != null ) {
				if ( node.getContent( childFilter ).isEmpty() )
					return null;
			}
			else if ( node.getChildren().isEmpty() ) {
				return null;
			}
			return node;
		}
	}
}
