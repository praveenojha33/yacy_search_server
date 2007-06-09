//User_p.java 
//-----------------------
//part of the AnomicHTTPD caching proxy
//(C) by Michael Peter Christen; mc@anomic.de
//first published on http://www.anomic.de
//Frankfurt, Germany, 2004
//
//This File is contributed by Alexander Schier
//last major change: 30.09.2005
//
//This program is free software; you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation; either version 2 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program; if not, write to the Free Software
//Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//Using this software in any meaning (reading, learning, copying, compiling,
//running) means that you agree that the Author(s) is (are) not responsible
//for cost, loss of data or any harm that may be caused directly or indirectly
//by usage of this softare or this documentation. The usage of this software
//is on your own risk. The installation and usage (starting/running) of this
//software may allow other people or application to access your computer and
//any attached devices and is highly dependent on the configuration of the
//software which must be done by the user of the software; the author(s) is
//(are) also not responsible for proper configuration and usage of the
//software, even if provoked by documentation provided together with
//the software.
//
//Any changes to this file according to the GPL as documented in the file
//gpl.txt aside this file in the shipment you received can be done to the
//lines that follows this copyright notice here, but changes must not be
//done inside the copyright notive above. A re-distribution must contain
//the intact and unchanged copyright notice.
//Contributions and changes to the program code must be marked as such.


//You must compile this file with
//javac -classpath .:../Classes Message.java
//if the shell's current path is HTROOT

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;

import de.anomic.data.userDB;
import de.anomic.http.httpHeader;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverCodings;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;

public class User_p {
    
    public static serverObjects respond(httpHeader header, serverObjects post, serverSwitch env) {
        serverObjects prop = new serverObjects();
        plasmaSwitchboard sb = plasmaSwitchboard.getSwitchboard();
        userDB.Entry entry=null;
		prop.put("SUPERTEMPLATE", "/env/page.html"); //user Supertemplates

        //default values
        prop.put("current_user", "newuser");
        prop.put("username", "");
        prop.put("firstname", "");
        prop.put("lastname", "");
        prop.put("address", "");
        prop.put("timelimit", "");
        prop.put("timeused", "");
        String[] rightNames=userDB.Entry.RIGHT_NAMES.split(",");
        String[] rights=userDB.Entry.RIGHT_TYPES.split(",");
        int i;
        for(i=0;i<rights.length;i++){
        		prop.put("rights_"+i+"_name", rights[i]);
        		prop.put("rights_"+i+"_friendlyName", rightNames[i]);
        		prop.put("rights_"+i+"_set", 0);
        }
        prop.put("rights", i);
        
        prop.put("users", 0);

        if(sb.userDB == null)
            return prop;
        
        if(post == null){
			//do nothing
            
        //user != current_user
        //user=from userlist
        //current_user = edited user
		} else if(post.containsKey("user") && !((String)post.get("user")).equals("newuser")){
			if(post.containsKey("change_user")){
	            //defaults for newuser are set above                
		        entry=sb.userDB.getEntry((String)post.get("user"));
		        // program crashes if a submit with emty username was made on previous mask and the user clicked on the 
		        // link: "If you want to manage more Users, return to the user page." (parameter "user" is empty)
                if (entry != null) {
    			    //TODO: set username read-only in html
                    prop.put("current_user", post.get("user"));
    	                prop.put("username", post.get("user"));
    	                prop.put("firstname", entry.getFirstName());
    	                prop.put("lastname", entry.getLastName());
    	                prop.put("address", entry.getAddress());
    	                prop.put("timelimit", entry.getTimeLimit());
    	                prop.put("timeused", entry.getTimeUsed());
    	                for(i=0;i<rights.length;i++){
    	                		prop.put("rights_"+i+"_set", (entry.hasRight(rights[i])?1:0));
    	                }
    	                prop.put("rights", i);
                }
			}else if( post.containsKey("delete_user") && !((String)post.get("user")).equals("newuser") ){
				sb.userDB.removeEntry((String)post.get("user"));
			}
        } else if(post.containsKey("change")) { //New User / edit User
            prop.put("text", 0);
            prop.put("error", 0);

            
            String username=(String)post.get("username");
            String pw=(String)post.get("password");
            String pw2=(String)post.get("password2");
            if(! pw.equals(pw2)){
                prop.put("error", 2); //PW does not match
                return prop;
            }
            String firstName=(String)post.get("firstname");
            String lastName=(String)post.get("lastname");
            String address=(String)post.get("address");
            String timeLimit=(String)post.get("timelimit");
            String timeUsed=(String)post.get("timeused");
            HashMap rightsSet=new HashMap();
            for(i=0;i<rights.length;i++){
        	    		rightsSet.put(rights[i], post.containsKey(rights[i])&&((String)post.get(rights[i])).equals("on") ? "true" : "false");
            }
            HashMap mem=new HashMap();
            if( post.get("current_user").equals("newuser")){ //new user
                
				if(!pw.equals("")){ //change only if set
	                mem.put(userDB.Entry.MD5ENCODED_USERPWD_STRING, serverCodings.encodeMD5Hex(username+":"+pw));
				}
				mem.put(userDB.Entry.USER_FIRSTNAME, firstName);
				mem.put(userDB.Entry.USER_LASTNAME, lastName);
				mem.put(userDB.Entry.USER_ADDRESS, address);
				mem.put(userDB.Entry.TIME_LIMIT, timeLimit);
				mem.put(userDB.Entry.TIME_USED, timeUsed);
				for(i=0;i<rights.length;i++)
					mem.put(rights[i], (String)rightsSet.get(rights[i]));

                try{
                    entry=sb.userDB.createEntry(username, mem);
                    sb.userDB.addEntry(entry);
                    prop.put("text_username", username);
                    prop.put("text", 1);
                }catch(IllegalArgumentException e){
                    prop.put("error", 3);
                }
                
                
            } else { //edit user

                entry = sb.userDB.getEntry(username);
				if(entry != null){
	                try{
						if(! pw.equals("")){
			                entry.setProperty(userDB.Entry.MD5ENCODED_USERPWD_STRING, serverCodings.encodeMD5Hex(username+":"+pw));
						}
						entry.setProperty(userDB.Entry.USER_FIRSTNAME, firstName);
						entry.setProperty(userDB.Entry.USER_LASTNAME, lastName);
						entry.setProperty(userDB.Entry.USER_ADDRESS, address);
						entry.setProperty(userDB.Entry.TIME_LIMIT, timeLimit);
						entry.setProperty(userDB.Entry.TIME_USED, timeUsed);
						for(i=0;i<rights.length;i++)
							entry.setProperty(rights[i], (String)rightsSet.get(rights[i]));
		            }catch (IOException e){
					}
                }else{
					prop.put("error", 1);
				}
				prop.put("text_username", username);
				prop.put("text", 2);
            }//edit user
			prop.put("username", username);
        }
		
		//Generate Userlist
        Iterator it = sb.userDB.iterator(true);
        int numUsers=0;
        while(it.hasNext()){
            entry = (userDB.Entry)it.next();
            prop.put("users_"+numUsers+"_user", entry.getUserName());
            numUsers++;
        }
        prop.put("users", numUsers);

        // return rewrite properties
        return prop;
    }
}
