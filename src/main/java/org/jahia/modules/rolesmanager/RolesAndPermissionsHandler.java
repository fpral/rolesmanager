package org.jahia.modules.rolesmanager;

import org.apache.commons.lang.StringUtils;
import org.apache.jackrabbit.util.ISO9075;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.content.JCRSessionWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContextHolder;

import javax.jcr.NodeIterator;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import java.io.Serializable;
import java.util.*;

public class RolesAndPermissionsHandler implements Serializable {

    private static Logger logger = LoggerFactory.getLogger(RolesAndPermissionsHandler.class);

//    enum Scope {CONTENT, SITE, SERVER_SETTINGS, STUDIO, JCR, OTHER};


    @Autowired
    private transient RoleTypeConfiguration roleTypes;

    private RoleBean roleBean = new RoleBean();

    private String currentContext;
    private String currentGroup;

    private transient Map<String,List<JCRNodeWrapper>> allPermissions;

    public RolesAndPermissionsHandler() {

//        defaultTabsForRoleByScope = new HashMap<RoleType, List<String>>();
//        defaultTabsForRoleByScope.put(RoleType.SERVER_ROLE, Arrays.asList("scope." + Scope.SERVER_SETTINGS.name(), "scope." + Scope.SITE.name(), "scope." + Scope.CONTENT.name(), "scope." + Scope.JCR.name()));
//        defaultTabsForRoleByScope.put(RoleType.SITE_ROLE, Arrays.asList("scope." + Scope.SITE.name(), "scope." + Scope.CONTENT.name(), "scope." + Scope.JCR.name()));
//        defaultTabsForRoleByScope.put(RoleType.EDIT_ROLE, Arrays.asList("scope." + Scope.CONTENT.name(), "scope." + Scope.JCR.name(), "context.$currentSite"));
//        defaultTabsForRoleByScope.put(RoleType.LIVE_ROLE, Arrays.asList("scope." + Scope.CONTENT.name(), "scope." + Scope.JCR.name()));

    }

    public RoleTypeConfiguration getRoleTypes() {
        return roleTypes;
    }

    public RoleBean getRoleBean() {
        return roleBean;
    }

    public void setRoleBean(RoleBean roleBean) {
        this.roleBean = roleBean;
        this.currentContext = "current";
        this.currentGroup = roleBean.getPermissions().get(currentContext).keySet().iterator().next();
    }

    private JCRSessionWrapper getSession() throws RepositoryException {
        return JCRSessionFactory.getInstance().getCurrentUserSession("default", LocaleContextHolder.getLocale());
    }

    public Map<String, List<RoleBean>> getRoles() throws RepositoryException {

        QueryManager qm = getSession().getWorkspace().getQueryManager();
        Query q = qm.createQuery("select * from [jnt:role]", Query.JCR_SQL2);
        Map<String, List<RoleBean>> all = new LinkedHashMap<String, List<RoleBean>>();
        for (RoleType roleType : roleTypes.getValues()) {
            all.put(roleType.getName(), new ArrayList<RoleBean>());
        }

        NodeIterator ni = q.execute().getNodes();
        while (ni.hasNext()) {
            JCRNodeWrapper next = (JCRNodeWrapper) ni.next();
            RoleBean role = getRole(next.getIdentifier());
            String key = role.getRoleType().getName();
            if (!all.containsKey(key)) {
                all.put(key, new ArrayList<RoleBean>());
            }
            all.get(key).add(role);
        }
        for (List<RoleBean> roleBeans : all.values()) {
            Collections.sort(roleBeans, new Comparator<RoleBean>() {
                @Override
                public int compare(RoleBean o1, RoleBean o2) {
                    return o1.getName().compareTo(o2.getName());
                }
            });
        }

        return all;
    }

    public RoleBean getRole(String uuid) throws RepositoryException {
        JCRSessionWrapper currentUserSession = getSession();

        JCRNodeWrapper role = currentUserSession.getNodeByIdentifier(uuid);

        RoleBean roleBean = new RoleBean();
        roleBean.setUuid(uuid);
        roleBean.setName(role.getName());
        roleBean.setDepth(role.getDepth());
        if (role.hasProperty("jcr:title")) {
            roleBean.setTitle(role.getProperty("jcr:title").getString());
        }
        if (role.hasProperty("jcr:description")) {
            roleBean.setDescription(role.getProperty("jcr:description").getString());
        }
        if (role.hasProperty("j:hidden")) {
            roleBean.setHidden(role.getProperty("j:hidden").getBoolean());
        }

        String roleGroup = role.getProperty("j:roleGroup").getString();

        RoleType roleType = roleTypes.get(roleGroup);
        roleBean.setRoleType(roleType);

        List<String> setPermIds = new ArrayList<String>();
        if (role.hasProperty("j:permissions")) {
            Value[] values = role.getProperty("j:permissions").getValues();
            for (Value value : values) {
                setPermIds.add(value.getString());
            }
        }

        List<String> tabs = new ArrayList<String>(roleBean.getRoleType().getScopes());

        Map<String, List<String>> setExternalPermIds = new HashMap<String, List<String>>();
        NodeIterator ni = role.getNodes();
        while (ni.hasNext()) {
            JCRNodeWrapper next = (JCRNodeWrapper) ni.next();
            if (next.isNodeType("jnt:externalPermissions")) {
                try {
                    String path = next.getProperty("j:path").getString();
                    setExternalPermIds.put(path, new ArrayList<String>());
                    Value[] values = next.getProperty("j:permissions").getValues();
                    for (Value value : values) {
                        setExternalPermIds.get(path).add(value.getString());
                        if (!tabs.contains(path)) {
                            tabs.add(path);
                        }
                    }
                } catch (RepositoryException e) {
                    System.out.println(next.getPath());
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                } catch (IllegalStateException e) {
                    System.out.println(next.getPath());
                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                }
            }
        }

        Map<String, Map<String, Map<String,PermissionBean>>> permsForRole = new LinkedHashMap<String, Map<String, Map<String,PermissionBean>>>();
        roleBean.setPermissions(permsForRole);

        for (String tab : tabs) {
            addPermissionsForTab(roleBean, tab, setPermIds, setExternalPermIds);
        }


        return roleBean;
    }

    public RoleBean addRole(String roleName, String scope) throws RepositoryException {
        JCRSessionWrapper currentUserSession = getSession();
        JCRNodeWrapper role = currentUserSession.getNode("/roles").addNode(roleName, "jnt:role");
        RoleType roleType = roleTypes.get(scope);
        role.setProperty("j:roleGroup", roleType.getName());
        role.setProperty("j:privilegedAccess", roleType.isPrivileged());
        if (roleType.getNodeType() != null) {
            role.setProperty("j:nodeTypes", new Value[]{currentUserSession.getValueFactory().createValue(roleType.getNodeType())});
        }
        role.setProperty("j:roleGroup", roleType.getName());

        currentUserSession.save();
        return getRole(role.getIdentifier());
    }

    private void addPermissionsForTab(RoleBean roleBean, String context, List<String> setPermIds, Map<String, List<String>> setExternalPermIds) throws RepositoryException {
        final Map<String, Map<String, Map<String,PermissionBean>>> permissions = roleBean.getPermissions();
        if (!permissions.containsKey(context)) {
            permissions.put(context, new LinkedHashMap<String, Map<String, PermissionBean>>());
        }
        Map<String, List<JCRNodeWrapper>> allPermissions = getPermissions();

        if (context.equals("current")) {
            List<JCRNodeWrapper> perms = new ArrayList<JCRNodeWrapper>(allPermissions.get("nt:base"));

            String type = "nt:base";

            if (roleBean.getRoleType().getNodeType() != null && roleTypes.getPermissionsGroups().containsKey(roleBean.getRoleType().getNodeType())) {
                type = roleBean.getRoleType().getNodeType();
            }
            for (String s : roleTypes.getPermissionsGroups().get(type)) {
                permissions.get(context).put(s, new TreeMap<String, PermissionBean>());
            }

            if (!type.equals("nt:base") && allPermissions.containsKey(type)) {
                perms.addAll(allPermissions.get(type));
            }

            Map<String,String> allGroups = new HashMap<String,String>();
            for (String s : permissions.get(context).keySet()) {
                for (String s1 : Arrays.asList(s.split(","))) {
                    allGroups.put(s1,s);
                }
            }

            for (JCRNodeWrapper permissionNode : perms) {
                JCRNodeWrapper permissionGroup = (JCRNodeWrapper) permissionNode.getAncestor(2);
                if (allGroups.containsKey(permissionGroup.getName())) {
                    Map<String,PermissionBean> p = permissions.get(context).get(allGroups.get(permissionGroup.getName()));
                    if (!permissionNode.hasProperty("j:requirePrivileged") || permissionNode.getProperty("j:requirePrivileged").getBoolean() == roleBean.getRoleType().isPrivileged()) {
                        PermissionBean bean = new PermissionBean();
                        bean.setUuid(permissionNode.getIdentifier());
                        bean.setParentPath(permissionNode.getParent().getPath());
                        bean.setName(permissionNode.getName());
                        bean.setPath(permissionNode.getPath());
                        bean.setDepth(permissionNode.getDepth());
                        bean.setScope(getScope(permissionNode));
                        PermissionBean parentBean = p.get(bean.getParentPath());
                        if (setPermIds.contains(permissionNode.getIdentifier()) || (parentBean != null && parentBean.isSet())) {
                            bean.setSet(true);
                            while (parentBean != null && !parentBean.isSet()) {
                                parentBean.setPartialSet(true);
                                parentBean = p.get(parentBean.getParentPath());
                            }
                        }
                        p.put(permissionNode.getPath(), bean);
                    }
                }
            }
        } else {
            List<JCRNodeWrapper> perms = new ArrayList<JCRNodeWrapper>(allPermissions.get("nt:base"));

            String type="nt:base";

            if (context.equals("currentSite")) {
                type = "jnt:virtualsite";
            } else {
                try {
                    JCRNodeWrapper contextNode = getSession().getNode(context);
                    String ntname = contextNode.getPrimaryNodeTypeName();
                    if (roleTypes.getPermissionsGroups().containsKey(ntname)) {
                        type = ntname;
                    }
                } catch (RepositoryException e) {
                    e.printStackTrace();
                }
            }

            for (String s : roleTypes.getPermissionsGroups().get(type)) {
                permissions.get(context).put(s, new TreeMap<String, PermissionBean>());
            }

            if (!type.equals("nt:base") && allPermissions.containsKey(type)) {
                perms.addAll(allPermissions.get(type));
            }

            Map<String,String> allGroups = new HashMap<String,String>();
            for (String s : permissions.get(context).keySet()) {
                for (String s1 : Arrays.asList(s.split(","))) {
                    allGroups.put(s1,s);
                }
            }

            for (JCRNodeWrapper permissionNode : perms) {
                JCRNodeWrapper permissionGroup = (JCRNodeWrapper) permissionNode.getAncestor(2);
                if (allGroups.containsKey(permissionGroup.getName())) {
                    Map<String,PermissionBean> p = permissions.get(context).get(allGroups.get(permissionGroup.getName()));
                    if (!permissionNode.hasProperty("j:requirePrivileged") || permissionNode.getProperty("j:requirePrivileged").getBoolean() == roleBean.getRoleType().isPrivileged()) {
                        ExternalPermissionBean bean = new ExternalPermissionBean();
                        bean.setUuid(permissionNode.getIdentifier());
                        bean.setParentPath(permissionNode.getParent().getPath());
                        bean.setName(permissionNode.getName());
                        bean.setPath(permissionNode.getPath());
                        bean.setDepth(permissionNode.getDepth());
                        bean.setTargetPath(context);
                        bean.setScope(getScope(permissionNode));
                        PermissionBean parentBean = p.get(bean.getParentPath());
                        if ((setExternalPermIds.get(context) != null && setExternalPermIds.get(context).contains(permissionNode.getIdentifier()))
                                || (parentBean != null && parentBean.isSet())) {
                            bean.setSet(true);
                            while (parentBean != null && !parentBean.isSet()) {
                                parentBean.setPartialSet(true);
                                parentBean = p.get(parentBean.getParentPath());
                            }
                        }
                        p.put(permissionNode.getPath(), bean);
                    }
                }
            }
        }
    }

    public String getCurrentContext() {
        return currentContext;
    }

    public void setCurrentContext(String tab) {
        currentContext = tab;
        this.currentGroup = roleBean.getPermissions().get(currentContext).keySet().iterator().next();
    }

    public String getCurrentGroup() {
        return currentGroup;
    }

    public void setCurrentGroup(String currentGroup) {
        this.currentGroup = currentGroup;
    }

    private String getScope(JCRNodeWrapper node) throws RepositoryException {
        String scope = "nt:base";

//        if (node.getPath().startsWith("/permissions/repository-permissions")) {
//            scope = Scope.JCR;
//        }
        if (node.hasProperty("j:nodeTypes")) {
            Set<String> s = new HashSet<String>();
            Value[] values = node.getProperty("j:nodeTypes").getValues();
            for (Value value : values) {
                return value.getString();
//                scope = getScopeForType(value.getString());
//                if (scope != Scope.OTHER) {
//                    return scope;
//                }
            }

        }
        return scope;
    }

//    private Scope getScopeForType(String s) throws RepositoryException {
//        Scope scope = Scope.OTHER;
//        if (s.equals("jnt:globalSettings")) {
//            scope = Scope.SERVER_SETTINGS;
//        } else if (s.equals("jnt:virtualsite")) {
//            scope = Scope.SITE;
//        } else if (s.equals("jnt:modules")) {
//            scope = Scope.STUDIO;
//        }
//        return scope;
//    }

    public void storeValues(String[] selectedValues, String[] partialSelectedValues) {
        Map<String, PermissionBean> permissionBeans = roleBean.getPermissions().get(currentContext).get(currentGroup);
        List<String> perms = selectedValues != null ? Arrays.asList(selectedValues) : new ArrayList<String>();
        for (PermissionBean permissionBean : permissionBeans.values()) {
            permissionBean.setSet(perms.contains(permissionBean.getPath()));
        }

        perms = partialSelectedValues != null ? Arrays.asList(partialSelectedValues) : new ArrayList<String>();
        for (PermissionBean permissionBean : permissionBeans.values()) {
            permissionBean.setPartialSet(perms.contains(permissionBean.getPath()));
        }
    }

    public void addContext(String newContext) throws RepositoryException {
        if (!newContext.startsWith("/")) {
            return;
        }

        String tab = newContext;
        if (!roleBean.getPermissions().containsKey(tab)) {
            addPermissionsForTab(roleBean, tab, new ArrayList<String>(), new HashMap<String, List<String>>());
        }
        setCurrentContext(tab);
    }

    public void save() throws RepositoryException {
        JCRSessionWrapper currentUserSession = getSession();

        List<Value> permissionsValues = new ArrayList<Value>();
        Map<String, List<Value>> externalPermissions = new HashMap<String, List<Value>>();

        for (Map.Entry<String, Map<String, Map<String, PermissionBean>>> entry : roleBean.getPermissions().entrySet()) {
            if (entry.getKey().equals("current")) {
                for (Map<String, PermissionBean> map : entry.getValue().values()) {
                    for (PermissionBean bean : map.values()) {
                        PermissionBean parentBean = map.get(bean.getParentPath());
                        if (bean.isSet() && (parentBean == null || !parentBean.isSet())) {
                            permissionsValues.add(currentUserSession.getValueFactory().createValue(bean.getUuid(), PropertyType.WEAKREFERENCE));
                        }
                    }
                }
            }
            if (entry.getKey().startsWith("context.")) {
                String path = StringUtils.substringAfter(entry.getKey(), "context.");
                ArrayList<Value> values = new ArrayList<Value>();
                externalPermissions.put(path, values);
                for (Map<String, PermissionBean> map : entry.getValue().values()) {
                    for (PermissionBean bean : map.values()) {
                        PermissionBean parentBean = map.get(bean.getParentPath());
                        if (bean.isSet() && (parentBean == null || !parentBean.isSet())) {
                            values.add(currentUserSession.getValueFactory().createValue(bean.getUuid(), PropertyType.WEAKREFERENCE));
                        }
                    }
                }
            }
//            if (entry.getKey().startsWith("special.")) {
//
//            }
        }

        JCRNodeWrapper role = currentUserSession.getNodeByIdentifier(roleBean.getUuid());
        role.setProperty("j:permissions", permissionsValues.toArray(new Value[permissionsValues.size()]));
        for (Map.Entry<String, List<Value>> s : externalPermissions.entrySet()) {
            String key = s.getKey();
            if (key.equals("/")) {
                key = "root-access";
            } else {
                key = ISO9075.encode(key.substring(1).replace("/", "-")) + "-access";
            }
            if (!role.hasNode(key)) {
                JCRNodeWrapper extPermissions = role.addNode(key, "jnt:externalPermissions");
                extPermissions.setProperty("j:path", s.getKey());
                extPermissions.setProperty("j:permissions", s.getValue().toArray(new Value[s.getValue().size()]));
            } else {
                role.getNode(key).setProperty("j:permissions", s.getValue().toArray(new Value[s.getValue().size()]));
            }

        }
        role.setProperty("jcr:title", roleBean.getTitle());
        role.setProperty("jcr:description", roleBean.getDescription());
        role.setProperty("j:hidden", roleBean.isHidden());
        currentUserSession.save();
    }

    public Map<String, List<JCRNodeWrapper>> getPermissions() throws RepositoryException {
        if (allPermissions != null) {
            return allPermissions;
        }

        allPermissions = new LinkedHashMap<String, List<JCRNodeWrapper>>();
        JCRSessionWrapper currentUserSession = getSession();

//        for (Scope scope : Scope.values()) {
//            allPermissions.put(scope.name(), new ArrayList<JCRNodeWrapper>());
//        }


        QueryManager qm = currentUserSession.getWorkspace().getQueryManager();
        String statement = "select * from [jnt:permission]";

        Query q = qm.createQuery(statement, Query.JCR_SQL2);
        NodeIterator ni = q.execute().getNodes();
        while (ni.hasNext()) {
            JCRNodeWrapper next = (JCRNodeWrapper) ni.next();
            if (next.getDepth() > 1) {
                String scope = getScope((JCRNodeWrapper)next.getAncestor(2));
                if (!allPermissions.containsKey(scope)) {
                    allPermissions.put(scope,new ArrayList<JCRNodeWrapper>());
                }
                allPermissions.get(scope).add(next);
            }
        }
        for (List<JCRNodeWrapper> list : allPermissions.values()) {
            Collections.sort(list, new Comparator<JCRNodeWrapper>() {
                @Override
                public int compare(JCRNodeWrapper o1, JCRNodeWrapper o2) {
                    return o1.getPath().compareTo(o2.getPath());
                }
            });
        }

        return allPermissions;
    }

    public void storeDetails(String title, String description, Boolean hidden) {
        roleBean.setTitle(title);
        roleBean.setDescription(description);
        roleBean.setHidden(hidden != null && hidden);
    }

}