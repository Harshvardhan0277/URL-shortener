// ====================================================================
// URL SHORTENER APPLICATION - JavaScript
// ====================================================================

// Configuration
const API_BASE = '';
const API_URLS = '/api/urls';
const API_ANALYTICS = '/api/analytics';

// DOM Elements
const urlForm = document.getElementById('urlForm');
const originalUrlInput = document.getElementById('originalUrl');
const submitBtn = document.getElementById('submitBtn');
const urlError = document.getElementById('urlError');
const resultContainer = document.getElementById('resultContainer');
const successMsg = document.getElementById('successMsg');
const shortUrlDisplay = document.getElementById('shortUrlDisplay');
const copyBtn = document.getElementById('copyBtn');
const analyzeBtn = document.getElementById('analyzeBtn');
const quickStats = document.getElementById('quickStats');
const totalClicksDisplay = document.getElementById('totalClicks');
const createdDateDisplay = document.getElementById('createdDate');
const lastClickDateDisplay = document.getElementById('lastClickDate');
const shortCodeDisplay = document.getElementById('shortCodeDisplay');

// Analytics Elements
const analyticsSearchCode = document.getElementById('analyticsSearchCode');
const searchAnalyticsBtn = document.getElementById('searchAnalyticsBtn');
const analyticsError = document.getElementById('analyticsError');
const analyticsResult = document.getElementById('analyticsResult');
const topUrlsList = document.getElementById('topUrlsList');

let currentShortCode = null;

// ====================================================================
// INITIALIZATION
// ====================================================================

document.addEventListener('DOMContentLoaded', function() {
    setupEventListeners();
    loadTopUrls();
});

// ====================================================================
// EVENT LISTENERS
// ====================================================================

function setupEventListeners() {
    // URL Form
    urlForm.addEventListener('submit', handleUrlSubmit);
    copyBtn.addEventListener('click', handleCopyClick);
    analyzeBtn.addEventListener('click', handleAnalyzeClick);

    // Analytics
    searchAnalyticsBtn.addEventListener('click', handleSearchAnalytics);
    analyticsSearchCode.addEventListener('keypress', function(e) {
        if (e.key === 'Enter') {
            handleSearchAnalytics();
        }
    });
}

// ====================================================================
// URL SHORTENING
// ====================================================================

async function handleUrlSubmit(e) {
    e.preventDefault();
    
    const url = originalUrlInput.value.trim();
    
    // Clear previous errors
    urlError.classList.add('d-none');
    urlError.textContent = '';
    
    // Validate URL
    if (!isValidUrl(url)) {
        showUrlError('Please enter a valid URL (e.g., https://example.com)');
        return;
    }
    
    submitBtn.disabled = true;
    submitBtn.innerHTML = '<span class="spinner-border spinner-border-sm me-2" role="status" aria-hidden="true"></span>Shortening...';
    
    try {
        const response = await fetch(API_URLS, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ originalUrl: url })
        });
        
        if (!response.ok) {
            const errorData = await response.json();
            throw new Error(errorData.message || 'Failed to create short URL');
        }
        
        const data = await response.json();
        handleUrlCreated(data, response.status);
        
    } catch (error) {
        showUrlError(error.message || 'An error occurred. Please try again.');
        console.error('Error:', error);
    } finally {
        submitBtn.disabled = false;
        submitBtn.innerHTML = '<i class="fas fa-cut me-2"></i>Shorten';
    }
}

function handleUrlCreated(data, status) {
    currentShortCode = data.shortCode;
    const shortUrl = `${window.location.origin}/r/${data.shortCode}`;
    
    // Show success message
    const isNew = status === 201;
    successMsg.innerHTML = isNew 
        ? '<strong>✓ Success!</strong> Your short URL has been created.'
        : '<strong>ℹ Note:</strong> This URL was already shortened. Here\'s your short code.';
    
    // Display short URL
    shortUrlDisplay.textContent = shortUrl;
    
    // Update stats
    shortCodeDisplay.textContent = data.shortCode;
    totalClicksDisplay.textContent = data.clickCount || '0';
    createdDateDisplay.textContent = formatDate(data.createdAt);
    lastClickDateDisplay.textContent = data.lastAccessedAt ? formatDate(data.lastAccessedAt) : '—';
    
    // Show results
    resultContainer.classList.remove('d-none');
    quickStats.classList.remove('d-none');
    
    // Clear form
    originalUrlInput.value = '';
    originalUrlInput.focus();
    
    // Scroll to results
    setTimeout(() => {
        resultContainer.scrollIntoView({ behavior: 'smooth', block: 'center' });
    }, 100);
}

function showUrlError(message) {
    urlError.textContent = message;
    urlError.classList.remove('d-none');
}

// ====================================================================
// COPY TO CLIPBOARD
// ====================================================================

async function handleCopyClick() {
    const shortUrl = shortUrlDisplay.textContent;
    
    try {
        await navigator.clipboard.writeText(shortUrl);
        
        // Show copy feedback
        const originalText = copyBtn.innerHTML;
        copyBtn.innerHTML = '<i class="fas fa-check me-1"></i>Copied!';
        copyBtn.classList.add('btn-success');
        copyBtn.classList.remove('btn-outline-primary');
        
        setTimeout(() => {
            copyBtn.innerHTML = originalText;
            copyBtn.classList.remove('btn-success');
            copyBtn.classList.add('btn-outline-primary');
        }, 2000);
        
        // Show notification
        showNotification('Copied to clipboard!', 'success');
        
    } catch (err) {
        showNotification('Failed to copy', 'error');
        console.error('Copy failed:', err);
    }
}

// ====================================================================
// ANALYTICS - INDIVIDUAL URL
// ====================================================================

async function handleAnalyzeClick() {
    if (!currentShortCode) return;
    analyticsSearchCode.value = currentShortCode;
    handleSearchAnalytics();
}

async function handleSearchAnalytics() {
    const code = analyticsSearchCode.value.trim();
    
    if (!code) {
        showAnalyticsError('Please enter a short code');
        return;
    }
    
    analyticsError.classList.add('d-none');
    analyticsResult.classList.add('d-none');
    
    try {
        const response = await fetch(`${API_ANALYTICS}/${code}`);
        
        if (!response.ok) {
            if (response.status === 404) {
                throw new Error('Short code not found');
            }
            throw new Error('Failed to fetch analytics');
        }
        
        const data = await response.json();
        displayAnalyticsResult(data);
        
    } catch (error) {
        showAnalyticsError(error.message);
        console.error('Error:', error);
    }
}

function displayAnalyticsResult(data) {
    document.getElementById('analyticsShortCode').textContent = data.shortCode;
    document.getElementById('analyticsClicks').textContent = data.totalClicks || '0';
    document.getElementById('analyticsCreated').textContent = formatDate(data.createdAt);
    document.getElementById('analyticsLastAccessed').textContent = data.lastAccessedAt 
        ? formatDate(data.lastAccessedAt) 
        : 'Never';
    
    // Store and display original URL if available
    if (data.originalUrl) {
        document.getElementById('analyticsOriginalUrl').textContent = data.originalUrl;
    }
    
    analyticsResult.classList.remove('d-none');
    
    // Scroll to results
    setTimeout(() => {
        analyticsResult.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    }, 100);
}

function showAnalyticsError(message) {
    analyticsError.textContent = message;
    analyticsError.classList.remove('d-none');
    analyticsResult.classList.add('d-none');
}

// ====================================================================
// ANALYTICS - TOP URLS
// ====================================================================

async function loadTopUrls() {
    try {
        const response = await fetch(`${API_ANALYTICS}/top?limit=10`);
        
        if (!response.ok) {
            throw new Error('Failed to fetch top URLs');
        }
        
        const data = await response.json();
        displayTopUrls(data);
        
    } catch (error) {
        console.error('Error loading top URLs:', error);
        topUrlsList.innerHTML = `
            <div class="alert alert-info">
                <i class="fas fa-info-circle me-2"></i>
                No analytics data available yet. Create some short URLs to see top links!
            </div>
        `;
    }
}

function displayTopUrls(urls) {
    if (urls.length === 0) {
        topUrlsList.innerHTML = `
            <div class="alert alert-info">
                <i class="fas fa-info-circle me-2"></i>
                No short URLs created yet. Start by creating your first short link above!
            </div>
        `;
        return;
    }
    
    topUrlsList.innerHTML = urls.map((url, index) => `
        <div class="top-url-item" style="animation-delay: ${index * 0.1}s">
            <div class="url-rank">#${index + 1}</div>
            <div class="url-info">
                <span class="url-code">${url.shortCode}</span>
                <div class="url-original" title="${url.originalUrl || ''}">${url.originalUrl || 'URL not available'}</div>
            </div>
            <div class="url-stats">
                <div class="url-stat">
                    <div class="url-stat-value">${url.clickCount}</div>
                    <div class="url-stat-label">Clicks</div>
                </div>
                <div class="url-stat">
                    <div class="url-stat-value">${formatDate(url.createdAt)}</div>
                    <div class="url-stat-label">Created</div>
                </div>
            </div>
        </div>
    `).join('');
}

// ====================================================================
// UTILITY FUNCTIONS
// ====================================================================

function isValidUrl(string) {
    try {
        const url = new URL(string);
        return url.protocol === 'http:' || url.protocol === 'https:';
    } catch (_) {
        return false;
    }
}

function formatDate(dateString) {
    if (!dateString) return '—';
    
    const date = new Date(dateString);
    
    // Check if date is valid
    if (isNaN(date.getTime())) {
        return '—';
    }
    
    const now = new Date();
    const diffInMs = now - date;
    const diffInSecs = Math.floor(diffInMs / 1000);
    const diffInMins = Math.floor(diffInSecs / 60);
    const diffInHours = Math.floor(diffInMins / 60);
    const diffInDays = Math.floor(diffInHours / 24);
    
    // If less than a minute ago
    if (diffInSecs < 60) {
        return 'Just now';
    }
    
    // If less than an hour ago
    if (diffInMins < 60) {
        return `${diffInMins}min ago`;
    }
    
    // If less than a day ago
    if (diffInHours < 24) {
        return `${diffInHours}h ago`;
    }
    
    // If less than a week ago
    if (diffInDays < 7) {
        return `${diffInDays}d ago`;
    }
    
    // Otherwise, show the full date
    return date.toLocaleDateString('en-US', {
        month: 'short',
        day: 'numeric',
        year: date.getFullYear() === now.getFullYear() ? undefined : 'numeric'
    });
}

function showNotification(message, type = 'success') {
    const notification = document.createElement('div');
    notification.className = 'copy-success';
    notification.textContent = message;
    
    // Adjust styling based on type
    if (type === 'error') {
        notification.style.background = 'linear-gradient(135deg, #dc3545 0%, #ff6b6b 100%)';
    }
    
    document.body.appendChild(notification);
    
    // Remove after 3 seconds
    setTimeout(() => {
        notification.style.animation = 'slideUp 0.3s ease forwards';
        setTimeout(() => notification.remove(), 300);
    }, 2500);
}

// ====================================================================
// REFRESH TOP URLS PERIODICALLY
// ====================================================================

// Refresh top URLs every 30 seconds for real-time updates
setInterval(() => {
    loadTopUrls();
}, 30000);
