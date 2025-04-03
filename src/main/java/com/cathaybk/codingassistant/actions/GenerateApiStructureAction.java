package com.cathaybk.codingassistant.actions;

import com.cathaybk.codingassistant.annotation.ApiMsgId;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

/**
 * 生成API相關結構的操作
 */
public class GenerateApiStructureAction extends AnAction {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        if (project == null)
            return;

        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || psiFile == null)
            return;

        // 獲取當前光標位置的元素
        int offset = editor.getCaretModel().getOffset();
        PsiElement element = psiFile.findElementAt(offset);
        if (element == null)
            return;

        // 找到包含元素的方法
        PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class);
        if (method == null) {
            Messages.showErrorDialog("請將光標置於API方法內", "錯誤");
            return;
        }

        // 檢查是否在Controller中
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null || !containingClass.getName().contains("Controller")) {
            Messages.showErrorDialog("只能在Controller類中使用此功能", "錯誤");
            return;
        }

        // 檢查是否是API方法
        boolean isApiMethod = false;
        for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
            String annoName = annotation.getQualifiedName();
            if (annoName != null && (annoName.contains("RequestMapping") ||
                    annoName.contains("GetMapping") ||
                    annoName.contains("PostMapping") ||
                    annoName.contains("PutMapping") ||
                    annoName.contains("DeleteMapping"))) {
                isApiMethod = true;
                break;
            }
        }

        if (!isApiMethod) {
            Messages.showErrorDialog("只能在API方法上使用此功能", "錯誤");
            return;
        }

        // 獲取或添加ApiMsgId
        String msgId = getMsgId(method, project);
        if (msgId == null)
            return;

        // 生成Service和ServiceImpl
        generateServiceStructure(project, method, containingClass, msgId);
    }

    /**
     * 獲取方法的MsgId，如果沒有則添加
     */
    private String getMsgId(PsiMethod method, Project project) {
        PsiAnnotation apiMsgIdAnnotation = method.getAnnotation(ApiMsgId.class.getName());

        if (apiMsgIdAnnotation != null) {
            PsiAnnotationMemberValue value = apiMsgIdAnnotation.findAttributeValue("value");
            if (value != null) {
                return value.getText().replace("\"", "");
            }
        }

        // 讓用戶輸入MsgId
        String msgId = Messages.showInputDialog(
                project,
                "請輸入API的消息ID",
                "添加ApiMsgId",
                Messages.getQuestionIcon());

        if (msgId == null || msgId.trim().isEmpty()) {
            return null;
        }

        // 添加註解
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        PsiAnnotation annotation = factory.createAnnotationFromText(
                "@com.cathaybk.codingassistant.annotation.ApiMsgId(\"" + msgId + "\")", method);

        WriteCommandAction.runWriteCommandAction(project, () -> {
            method.getModifierList().addAfter(annotation, null);
        });

        return msgId;
    }

    /**
     * 生成Service和ServiceImpl結構
     */
    private void generateServiceStructure(Project project, PsiMethod method, PsiClass controllerClass, String msgId) {
        // 從Controller名稱派生Service名稱
        String controllerName = controllerClass.getName();
        if (controllerName == null)
            return;

        String baseName = controllerName.replace("Controller", "");
        String serviceName = baseName + "Service";
        String serviceImplName = baseName + "ServiceImpl";

        // 檢查項目中是否已存在Service
        PsiClass serviceClass = JavaPsiFacade.getInstance(project)
                .findClass(controllerClass.getQualifiedName().replace(controllerName, serviceName),
                        controllerClass.getResolveScope());

        if (serviceClass == null) {
            // 創建Service接口
            createServiceInterface(project, controllerClass, serviceName, method, msgId);
        }

        // 檢查是否已存在ServiceImpl
        PsiClass serviceImplClass = JavaPsiFacade.getInstance(project)
                .findClass(controllerClass.getQualifiedName().replace(controllerName, serviceImplName),
                        controllerClass.getResolveScope());

        if (serviceImplClass == null) {
            // 創建ServiceImpl類
            createServiceImpl(project, controllerClass, serviceName, serviceImplName, method, msgId);
        }

        Messages.showInfoMessage(project, "成功生成Service結構", "成功");
    }

    /**
     * 創建Service接口
     */
    private void createServiceInterface(Project project, PsiClass controllerClass, String serviceName,
            PsiMethod apiMethod, String msgId) {
        String packageName = ((PsiJavaFile) controllerClass.getContainingFile()).getPackageName();
        packageName = packageName.replace(".controller", ".service");

        // 創建服務接口目錄
        PsiDirectory serviceDir = createPackageIfNeeded(project, packageName);
        if (serviceDir == null)
            return;

        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

        // 構建服務接口代碼
        StringBuilder serviceText = new StringBuilder();
        serviceText.append("package ").append(packageName).append(";\n\n");
        serviceText.append("import com.cathaybk.codingassistant.annotation.ApiMsgId;\n\n");
        serviceText.append("/**\n * ").append(serviceName).append("\n */\n");
        serviceText.append("@ApiMsgId(\"").append(msgId).append("\")\n");
        serviceText.append("public interface ").append(serviceName).append(" {\n\n");

        // 添加從API方法派生的服務方法
        String methodName = apiMethod.getName();
        String returnType = apiMethod.getReturnType() != null ? apiMethod.getReturnType().getPresentableText() : "void";

        serviceText.append("    /**\n     * ").append(methodName).append("\n     */\n");
        serviceText.append("    ").append(returnType).append(" ").append(methodName).append("(");

        // 添加參數
        PsiParameter[] parameters = apiMethod.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            PsiParameter param = parameters[i];
            if (i > 0)
                serviceText.append(", ");
            serviceText.append(param.getType().getPresentableText()).append(" ").append(param.getName());
        }

        serviceText.append(");\n}\n");

        // 創建服務接口文件
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                PsiFile serviceFile = PsiFileFactory.getInstance(project)
                        .createFileFromText(serviceName + ".java",
                                com.intellij.openapi.fileTypes.StdFileTypes.JAVA,
                                serviceText.toString());
                serviceDir.add(serviceFile);
            } catch (Exception ex) {
                Messages.showErrorDialog("創建Service接口失敗: " + ex.getMessage(), "錯誤");
            }
        });
    }

    /**
     * 創建ServiceImpl類
     */
    private void createServiceImpl(Project project, PsiClass controllerClass, String serviceName,
            String serviceImplName, PsiMethod apiMethod, String msgId) {
        String packageName = ((PsiJavaFile) controllerClass.getContainingFile()).getPackageName();
        String servicePackage = packageName.replace(".controller", ".service");
        String implPackage = servicePackage + ".impl";

        // 創建實現類目錄
        PsiDirectory implDir = createPackageIfNeeded(project, implPackage);
        if (implDir == null)
            return;

        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

        // 構建實現類代碼
        StringBuilder implText = new StringBuilder();
        implText.append("package ").append(implPackage).append(";\n\n");
        implText.append("import ").append(servicePackage).append(".").append(serviceName).append(";\n");
        implText.append("import org.springframework.stereotype.Service;\n");
        implText.append("import com.cathaybk.codingassistant.annotation.ApiMsgId;\n\n");

        implText.append("/**\n * ").append(serviceImplName).append("\n */\n");
        implText.append("@Service\n");
        implText.append("@ApiMsgId(\"").append(msgId).append("\")\n");
        implText.append("public class ").append(serviceImplName).append(" implements ").append(serviceName)
                .append(" {\n\n");

        // 添加方法實現
        String methodName = apiMethod.getName();
        String returnType = apiMethod.getReturnType() != null ? apiMethod.getReturnType().getPresentableText() : "void";

        implText.append("    @Override\n");
        implText.append("    public ").append(returnType).append(" ").append(methodName).append("(");

        // 添加參數
        PsiParameter[] parameters = apiMethod.getParameterList().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            PsiParameter param = parameters[i];
            if (i > 0)
                implText.append(", ");
            implText.append(param.getType().getPresentableText()).append(" ").append(param.getName());
        }

        implText.append(") {\n");

        // 添加默認實現
        if (!"void".equals(returnType)) {
            implText.append("        // TODO: 實現").append(methodName).append("方法\n");
            if ("String".equals(returnType)) {
                implText.append("        return \"\";\n");
            } else if ("int".equals(returnType) || "Integer".equals(returnType)) {
                implText.append("        return 0;\n");
            } else if ("long".equals(returnType) || "Long".equals(returnType)) {
                implText.append("        return 0L;\n");
            } else if ("boolean".equals(returnType) || "Boolean".equals(returnType)) {
                implText.append("        return false;\n");
            } else if ("double".equals(returnType) || "Double".equals(returnType)) {
                implText.append("        return 0.0;\n");
            } else if ("float".equals(returnType) || "Float".equals(returnType)) {
                implText.append("        return 0.0f;\n");
            } else {
                implText.append("        return null;\n");
            }
        } else {
            implText.append("        // TODO: 實現").append(methodName).append("方法\n");
        }

        implText.append("    }\n");
        implText.append("}\n");

        // 創建實現類文件
        WriteCommandAction.runWriteCommandAction(project, () -> {
            try {
                PsiFile implFile = PsiFileFactory.getInstance(project)
                        .createFileFromText(serviceImplName + ".java",
                                com.intellij.openapi.fileTypes.StdFileTypes.JAVA,
                                implText.toString());
                implDir.add(implFile);
            } catch (Exception ex) {
                Messages.showErrorDialog("創建ServiceImpl類失敗: " + ex.getMessage(), "錯誤");
            }
        });
    }

    /**
     * 創建包目錄（如果不存在）
     */
    private PsiDirectory createPackageIfNeeded(Project project, String packageName) {
        PsiPackage psiPackage = JavaPsiFacade.getInstance(project).findPackage(packageName);
        if (psiPackage != null && psiPackage.getDirectories().length > 0) {
            return psiPackage.getDirectories()[0];
        }

        // 創建包目錄
        PsiDirectory baseDir = null;
        String[] parts = packageName.split("\\.");
        String currentPackage = "";

        for (String part : parts) {
            currentPackage = currentPackage.isEmpty() ? part : currentPackage + "." + part;
            PsiPackage pkg = JavaPsiFacade.getInstance(project).findPackage(currentPackage);

            if (pkg != null && pkg.getDirectories().length > 0) {
                baseDir = pkg.getDirectories()[0];
                continue;
            }

            if (baseDir == null) {
                // 找不到基礎目錄
                Messages.showErrorDialog("無法創建包: " + packageName, "錯誤");
                return null;
            }

            // 修改這部分代碼，避免在 lambda 表達式中修改 baseDir 變數
            final PsiDirectory currentDir = baseDir;
            final String currentPart = part;
            
            // 創建一個引用以保存新創建的目錄
            final PsiDirectory[] newDir = new PsiDirectory[1];
            
            WriteCommandAction.runWriteCommandAction(project, () -> {
                try {
                    newDir[0] = currentDir.createSubdirectory(currentPart);
                } catch (Exception e) {
                    Messages.showErrorDialog("創建目錄失敗: " + e.getMessage(), "錯誤");
                }
            });
            
            // 如果成功創建了新目錄，則更新 baseDir
            if (newDir[0] != null) {
                baseDir = newDir[0];
            } else {
                return null; // 如果創建失敗，則退出
            }
        }

        return baseDir;
    }

    @Override
    public void update(@NotNull AnActionEvent e) {
        Project project = e.getProject();
        Editor editor = e.getData(CommonDataKeys.EDITOR);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);

        // 僅在Java文件中啟用
        e.getPresentation().setEnabled(project != null && editor != null &&
                psiFile instanceof PsiJavaFile);
    }
}